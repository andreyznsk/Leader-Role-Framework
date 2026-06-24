# Mail Settings Test Connection

## Goal

Verify the Mail Agent control-plane form exposes EWS auth settings and a connection test that does not start polling.

## Steps

1. Open `http://localhost:8082/ui/settings`.
2. Expand `Mail Agent`.
3. Confirm the form shows:
   - `Protocol`
   - `Server URL`
   - `Login`
   - `Password / secret`
   - `Authentication Type`
   - `Folders exclude`
   - `Test Connection`
4. Set `Protocol = ews`.
5. Set `Authentication Type = NTLM`.
6. Click `Test Connection`.
7. Confirm the request is sent to `POST /api/settings/control/plugins/mail/test-connection`.
8. Confirm the inline result shows:
   - `Connected`
   - `Exchange Version`
   - `Auth`
   - `Folders found`
9. Confirm no polling cycle is started and no processed-mail side effects happen.
