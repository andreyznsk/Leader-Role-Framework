#!/usr/bin/env python3
"""CR-MEM-036 REST + actual MCP SSE regression/performance scenario (stdlib only)."""
import datetime as dt
import json
import os
import queue
import sys
import threading
import time
import urllib.error
import urllib.request

BASE = os.environ.get('MEMORY_BASE_URL', 'http://localhost:8082')
SIZE = int(os.environ.get('RANGE_TEST_SIZE', '1000'))
ids = []
events = queue.Queue()
sse = None


def request(path, body=None, method=None):
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json'}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())


def read_sse():
    event = None
    for raw in sse:
        line = raw.decode().strip()
        if line.startswith('event:'):
            event = line[6:].strip()
        elif line.startswith('data:'):
            events.put((event, line[5:].strip()))


def rpc(method, params, call_id):
    code, _ = request(endpoint, {'jsonrpc': '2.0', 'id': call_id, 'method': method, 'params': params})
    assert code in (200, 202), code
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        event, data = events.get(timeout=max(0.01, deadline - time.monotonic()))
        if event == 'message':
            message = json.loads(data)
            if message.get('id') == call_id:
                assert 'error' not in message, message
                return message['result']
    raise AssertionError('MCP response timeout')


def tool(name, arguments, call_id):
    result = rpc('tools/call', {'name': name, 'arguments': arguments}, call_id)
    assert not result.get('isError'), result
    return json.loads(result['content'][0]['text'])



def read_only():
    global sse, endpoint
    assert request('/actuator/health') == (200, {'status': 'UP'})
    sse = urllib.request.urlopen(BASE + '/mcp/sse', timeout=60)
    threading.Thread(target=read_sse, daemon=True).start()
    event, endpoint = events.get(timeout=10)
    assert event == 'endpoint'
    rpc('initialize', {'protocolVersion': '2024-11-05', 'capabilities': {}, 'clientInfo': {'name': 'cr036-readonly', 'version': '1.0'}}, 1)
    assert request(endpoint, {'jsonrpc': '2.0', 'method': 'notifications/initialized'})[0] in (200,202)
    listed = rpc('tools/list', {}, 2)['tools']
    schema = next(t['inputSchema'] for t in listed if t['name'] == 'getTasksInRange')
    assert set(schema['required']) == {'from', 'to'}
    print('PASS tools/list: getTasksInRange required=from,to; status optional')
    query = {'from': '2026-09-01', 'to': '2026-11-29'}
    begin = time.perf_counter()
    code, rest = request('/api/tasks/range', query)
    elapsed = time.perf_counter()-begin
    assert code == 200 and elapsed < 2
    result = tool('getTasksInRange', query, 3)
    assert result == rest
    assert all(query['from'] <= t['dueDate'] <= query['to'] for t in result)
    print(f'PASS MCP vs REST: 90 days, rows={len(rest)}, REST elapsed={elapsed:.4f}s')
    for i,status in enumerate(['TODO', 'RESEARCH', 'IN_PROGRESS', 'DELEGATED', 'DONE', 'BLOCKED'], 4):
        result = tool('getTasksInRange', dict(query,status=status), i)
        code, rest_filtered = request('/api/tasks/range', dict(query,status=status))
        assert code == 200 and result == rest_filtered
        assert all(t['status'] == status for t in result)
        print(f'PASS MCP status={status}, rows={len(result)}')
    for i,(query,error) in enumerate([
        ({'from':'2026-11-02','to':'2026-11-01'}, 'invalid_date_range'),
        ({'from':'2026-02-30','to':'2026-11-01'}, 'invalid_date_format'),
        ({'from':'2026-01-01','to':'2027-01-02'}, 'range_too_large'),
        ({'from':'2026-01-01','to':'2026-01-01','status':'UNKNOWN'}, 'invalid_status')],10):
        code, body = request('/api/tasks/range', query)
        assert code == 400 and body['code'] == error
        result = rpc('tools/call', {'name':'getTasksInRange','arguments':query}, i)
        assert result.get('isError'), result
        print(f'PASS REST {error}; MCP isError=true')
    for i,status in enumerate([None,'DONE'],14):
        args={'date':'2026-11-11'}
        if status: args['status']=status
        actual=tool('getTasks',args,i)
        path='/api/tasks?date=2026-11-11'+('' if status is None else '&status='+status)
        code, expected=request(path)
        assert code == 200 and actual == expected
        print(f'PASS getTasks regression status={status}, rows={len(actual)}')
    print('PASS read-only MCP smoke; no operational data modified')


def fixtures():
    global sse, endpoint
    try:
        assert request('/actuator/health')[0] == 200
        start = dt.date(2091, 1, 1)
        end = start + dt.timedelta(days=89)
        # A different plan date ensures we really query dueDate.
        for index in range(SIZE + 2):
            offset = index % 90 if index < SIZE else (-1 if index == SIZE else 90)
            code, task = request('/api/tasks', {'title': 'CR036-e2e', 'date': '2090-01-01',
                                'dueDate': str(start + dt.timedelta(days=offset)),
                                'status': 'TODO' if index % 2 else 'DONE', 'source': 'MANUAL'})
            assert code == 201, (code, task)
            ids.append(task['id'])
        query = {'from': str(start), 'to': str(end)}
        begin = time.perf_counter()
        code, result = request('/api/tasks/range', query)
        elapsed = time.perf_counter() - begin
        assert code == 200
        expected = set(ids[:SIZE])
        assert expected <= {task['id'] for task in result}
        assert not set(ids[SIZE:]) & {task['id'] for task in result}
        assert elapsed < 2, elapsed
        print(f'PASS REST 90 days: seeded={SIZE + 2}, returned={len(result)}, elapsed={elapsed:.4f}s')
        code, filtered = request('/api/tasks/range', dict(query, status='DONE'))
        assert code == 200 and all(task['status'] == 'DONE' for task in filtered)
        assert set(ids[:SIZE:2]) <= {task['id'] for task in filtered}
        for body, error in [({'from': str(end), 'to': str(start)}, 'invalid_date_range'),
                            ({'from': '2091-02-30', 'to': str(end)}, 'invalid_date_format'),
                            ({'from': str(start), 'to': str(start + dt.timedelta(days=366))}, 'range_too_large'),
                            (dict(query, status='UNKNOWN'), 'invalid_status')]:
            code, result = request('/api/tasks/range', body)
            assert code == 400 and result['code'] == error, (code, result)
        print('PASS REST optional status and four validation codes')
        sse = urllib.request.urlopen(BASE + '/mcp/sse', timeout=60)
        threading.Thread(target=read_sse, daemon=True).start()
        event, endpoint = events.get(timeout=10)
        assert event == 'endpoint', (event, endpoint)
        rpc('initialize', {'protocolVersion': '2024-11-05', 'capabilities': {},
                          'clientInfo': {'name': 'cr036-e2e', 'version': '1.0'}}, 1)
        assert request(endpoint, {'jsonrpc': '2.0', 'method': 'notifications/initialized'})[0] in (200, 202)
        tools = rpc('tools/list', {}, 2)['tools']
        schema = next(t['inputSchema'] for t in tools if t['name'] == 'getTasksInRange')
        assert set(schema['required']) == {'from', 'to'}, schema
        result = tool('getTasksInRange', query, 3)
        assert expected <= {task['id'] for task in result}
        result = tool('getTasksInRange', dict(query, status='DONE'), 4)
        assert all(task['status'] == 'DONE' for task in result)
        daily = tool('getTasks', {'date': '2090-01-01', 'status': 'DONE'}, 5)
        assert set(ids[::2]) <= {task['id'] for task in daily}
        print('PASS MCP tools/list, range calls, optional status, getTasks regression')
    finally:
        failures = []
        for task_id in ids:
            try:
                code, _ = request(f'/api/tasks/{task_id}', method='DELETE')
                if code != 204:
                    failures.append((task_id, code))
            except Exception as error:
                failures.append((task_id, str(error)))
        print(f'Cleanup: archived {len(ids) - len(failures)} test tasks (existing DELETE semantics)')
        assert not failures, ('cleanup failures', failures)


if __name__ == '__main__':
    if '--read-only' in sys.argv:
        read_only()
    else:
        fixtures()
