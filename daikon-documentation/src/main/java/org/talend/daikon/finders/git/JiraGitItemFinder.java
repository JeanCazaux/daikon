package org.talend.daikon.finders.git;

import static java.util.concurrent.CompletableFuture.supplyAsync;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.atlassian.jira.rest.client.api.RestClientException;
import com.atlassian.jira.rest.client.api.domain.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.talend.daikon.finders.ItemFinder;
import org.talend.daikon.model.JiraReleaseNoteItem;
import org.talend.daikon.model.PullRequest;
import org.talend.daikon.model.ReleaseNoteItem;

import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.domain.Issue;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Finds Jira for release note based of Git history (finds Jira ids from Git commit messages).
 */
public class JiraGitItemFinder extends AbstractGitItemFinder implements ItemFinder {

    private static final Logger LOGGER = LoggerFactory.getLogger(JiraGitItemFinder.class);

    private final String jiraServerUrl;

    private final JiraRestClient client;

    public JiraGitItemFinder(String jiraServerUrl, JiraRestClient client, String version, String gitHubRepositoryUrl) {
        this(null, jiraServerUrl, client, version, gitHubRepositoryUrl);
    }

    public JiraGitItemFinder(String gitRepositoryPath, String jiraServerUrl, JiraRestClient client, String version,
            String gitHubRepositoryUrl) {
        super(version, gitRepositoryPath, gitHubRepositoryUrl);
        this.jiraServerUrl = jiraServerUrl;
        this.client = client;
    }

    @Override
    public Stream<? extends ReleaseNoteItem> find() {
        try {
            final Map<String, Issue> issueCache = new HashMap<>();
            return supplyAsync(() -> { // Get all Jira id from commits
                return getGitCommits() //
                        .filter(c -> !c.getCommit().getShortMessage().contains("release")) //
                        .map(c -> new RawGitCommit(JIRA_DETECTION_PATTERN.matcher(c.getCommit().getShortMessage()),
                                c.getPullRequest(), c.getCommit().getShortMessage())) //
                        .filter(rawGitCommit -> rawGitCommit.getJiraIdMatcher().matches()) //
                        .collect(Collectors.toList());
            }) //
                    .thenApply(jiraIds -> { // Get all Jira issues in one call
                        if (!jiraIds.isEmpty()) {
                            final String idList = jiraIds.stream() //
                                    .map(s -> "\"" + s.getJiraIdMatcher().group(1) + "\"") //
                                    .collect(Collectors.joining(", "));
                            final String jql = "id IN (" + idList + ")";
                            try {
                                final SearchResult results = client.getSearchClient().searchJql(jql, jiraIds.size(), 0, null)
                                        .claim();
                                for (Issue issue : results.getIssues()) {
                                    issueCache.put(issue.getKey(), issue);
                                }
                            } catch (RestClientException ex) {
                                // Log a warning but proceed (see https://jira.talendforge.org/browse/TDKN-349)
                                LOGGER.warn("Error looking for issues from JIRA", ex);
                            }
                        }
                        return jiraIds;
                    }) //
                    .thenApply(rawGitCommits -> { // Get Jira issue from previous "cache" to speed up Jira operations
                        return rawGitCommits.stream().map(rawGitCommit -> {
                            final String jiraId = rawGitCommit.getJiraIdMatcher().group(1);
                            Issue issue = issueCache.get(jiraId);
                            if (issue == null) {
                                // Issue can move to another id in Jira (issue id changed between git log and Jira)
                                try {
                                    issue = client.getIssueClient().getIssue(jiraId).claim();
                                } catch (RestClientException ex) {
                                    // Log a warning but proceed, as maybe the regular expression is incorrectly flagging
                                    // something as a JIRA
                                    LOGGER.warn("Error getting issue from JIRA", ex);
                                }
                            }
                            return new ProcessedJiraTuple(issue, rawGitCommit.getPullRequest(), rawGitCommit.getShortMessage());
                        });
                    }) //
                    .thenApply(processedJiraTuples -> processedJiraTuples.map(tuple -> new JiraReleaseNoteItem(tuple.getIssue(), //
                            jiraServerUrl, //
                            tuple.getPullRequest(), tuple.getShortMessage())) //
                    ) //
                    .get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Getter
    @AllArgsConstructor
    private static class RawGitCommit {

        Matcher jiraIdMatcher;

        PullRequest pullRequest;

        String shortMessage;
    }

    @Getter
    @AllArgsConstructor
    private static class ProcessedJiraTuple {

        Issue issue;

        PullRequest pullRequest;

        String shortMessage;
    }
}
