package ru.andreyz.common.jira;

import ru.andreyz.common.jira.dto.JiraAssignableUser;
import ru.andreyz.common.jira.dto.JiraConnectionResult;
import ru.andreyz.common.jira.dto.JiraCreateIssueRequest;
import ru.andreyz.common.jira.dto.JiraCreateIssueResult;
import ru.andreyz.common.jira.dto.JiraCurrentUser;
import ru.andreyz.common.jira.dto.JiraIssueType;
import ru.andreyz.common.jira.dto.JiraProject;

import java.util.List;
import java.util.Set;

public interface JiraClient {

    JiraConnectionResult testConnection();

    JiraCurrentUser getCurrentUser();

    List<JiraProject> getProjects(Set<String> allowedProjectKeys);

    List<JiraIssueType> getIssueTypes(String projectKey);

    List<JiraAssignableUser> getAssignableUsers(String projectKey, String query);

    JiraCreateIssueResult createIssue(JiraCreateIssueRequest request);
}
