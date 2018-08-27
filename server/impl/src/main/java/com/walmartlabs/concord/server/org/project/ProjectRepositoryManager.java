package com.walmartlabs.concord.server.org.project;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2018 Walmart Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */

import com.walmartlabs.concord.project.ProjectLoader;
import com.walmartlabs.concord.project.model.ProjectDefinition;
import com.walmartlabs.concord.server.audit.AuditAction;
import com.walmartlabs.concord.server.audit.AuditLog;
import com.walmartlabs.concord.server.audit.AuditObject;
import com.walmartlabs.concord.server.events.Events;
import com.walmartlabs.concord.server.events.ExternalEventResource;
import com.walmartlabs.concord.server.events.GithubWebhookService;
import com.walmartlabs.concord.server.org.ResourceAccessLevel;
import com.walmartlabs.concord.server.org.secret.SecretDao;
import com.walmartlabs.concord.server.org.secret.SecretManager;
import com.walmartlabs.concord.server.repository.RepositoryManager;
import org.jooq.DSLContext;
import org.sonatype.siesta.ValidationErrorsException;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Named
public class ProjectRepositoryManager {

    private final ProjectAccessManager projectAccessManager;
    private final SecretManager secretManager;
    private final RepositoryManager repositoryManager;
    private final SecretDao secretDao;
    private final RepositoryDao repositoryDao;
    private final ExternalEventResource externalEventResource;
    private final GithubWebhookService githubWebhookService;
    private final RepositoryValidator repositoryValidator;
    private final AuditLog auditLog;

    private final ProjectLoader loader = new ProjectLoader();

    @Inject
    public ProjectRepositoryManager(ProjectAccessManager projectAccessManager,
                                    SecretManager secretManager,
                                    RepositoryManager repositoryManager,
                                    SecretDao secretDao,
                                    RepositoryDao repositoryDao,
                                    ExternalEventResource externalEventResource,
                                    GithubWebhookService githubWebhookService,
                                    RepositoryValidator repositoryValidator,
                                    AuditLog auditLog) {

        this.projectAccessManager = projectAccessManager;
        this.secretManager = secretManager;
        this.repositoryManager = repositoryManager;
        this.secretDao = secretDao;
        this.repositoryDao = repositoryDao;
        this.externalEventResource = externalEventResource;
        this.githubWebhookService = githubWebhookService;
        this.repositoryValidator = repositoryValidator;
        this.auditLog = auditLog;
    }

    public void createOrUpdate(UUID projectId, RepositoryEntry entry) {
        repositoryDao.tx(tx -> createOrUpdate(tx, projectId, entry));
    }

    public void createOrUpdate(DSLContext tx, UUID projectId, RepositoryEntry entry) {
        ProjectEntry project = projectAccessManager.assertProjectAccess(projectId, ResourceAccessLevel.WRITER, false);

        UUID secretId = entry.getSecretId();
        if (secretId == null && entry.getSecretName() != null) {
            secretManager.assertAccess(project.getOrgId(), null, entry.getSecretName(), ResourceAccessLevel.READER, false);
        }

        UUID repoId = entry.getId();
        if (repoId == null) {
            repoId = repositoryDao.getId(projectId, entry.getName());
        }

        if (repoId == null) {
            insert(tx, project.getOrgId(), project.getOrgName(), projectId, project.getName(), entry, true);
        } else {
            update(tx, project.getOrgId(), project.getOrgName(), projectId, project.getName(), repoId, entry, true);
        }
    }

    public void delete(UUID projectId, String repoName) {
        ProjectEntry projEntry = projectAccessManager.assertProjectAccess(projectId, ResourceAccessLevel.WRITER, false);

        UUID repoId = repositoryDao.getId(projectId, repoName);
        if (repoId == null) {
            throw new ValidationErrorsException("Repository not found: " + repoName);
        }

        repositoryDao.delete(repoId);
        addAuditLog(
                projEntry.getOrgId(),
                projEntry.getOrgName(),
                projEntry.getId(),
                projEntry.getName(),
                projEntry.getRepositories().get(repoName),
                null);
    }

    public void insert(DSLContext tx, UUID orgId, String orgName, UUID projectId, String projectName, RepositoryEntry entry, boolean doAuditLog) {
        UUID secretId = entry.getSecretId();
        if (secretId == null && entry.getSecretName() != null) {
            secretId = secretDao.getId(orgId, entry.getSecretName());
        }

        UUID repoId = repositoryDao.insert(tx, projectId,
                entry.getName(), entry.getUrl(),
                trim(entry.getBranch()), trim(entry.getCommitId()),
                trim(entry.getPath()), secretId, false);

        githubWebhookService.register(projectId, entry.getName(), entry.getUrl());

        Map<String, Object> ev = Events.Repository.repositoryUpdated(orgName, projectName, entry.getName());
        externalEventResource.event(Events.CONCORD_EVENT, ev);

        if (doAuditLog) {
            RepositoryEntry newEntry = repositoryDao.get(tx, projectId, repoId);
            addAuditLog(
                    orgId,
                    orgName,
                    projectId,
                    projectName,
                    null,
                    newEntry
            );
        }
    }

    private void update(DSLContext tx, UUID orgId, String orgName, UUID projectId, String projectName, UUID repoId, RepositoryEntry entry, boolean doAuditLog) {
        RepositoryEntry prevEntry = repositoryDao.get(tx, projectId, repoId);

        UUID secretId = entry.getSecretId();
        if (secretId == null && entry.getSecretName() != null) {
            secretId = secretDao.getId(orgId, entry.getSecretName());
        }

        repositoryDao.update(tx, repoId,
                entry.getName(), entry.getUrl(),
                trim(entry.getBranch()), trim(entry.getCommitId()),
                trim(entry.getPath()), secretId);

        githubWebhookService.register(projectId, entry.getName(), entry.getUrl());

        Map<String, Object> ev = Events.Repository.repositoryUpdated(orgName, projectName, entry.getName());
        externalEventResource.event(Events.CONCORD_EVENT, ev);

        if (doAuditLog) {
            RepositoryEntry newEntry = repositoryDao.get(tx, projectId, repoId);
            addAuditLog(
                    orgId,
                    orgName,
                    projectId,
                    projectName,
                    prevEntry,
                    newEntry
            );
        }
    }

    public void validateRepository(UUID projectId, RepositoryEntry repositoryEntry) throws IOException {
        Path srcPath = repositoryManager.fetch(projectId, repositoryEntry);
        ProjectDefinition pd = loader.loadProject(srcPath);
        repositoryValidator.validate(pd);
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }

        s = s.trim();

        if (s.isEmpty()) {
            return null;
        }

        return s;
    }

    private void addAuditLog(UUID orgId, String orgName, UUID projectId, String projectName, RepositoryEntry prevRepoEntry, RepositoryEntry newRepoEntry) {
        ProjectEntry prevEntry = new ProjectEntry(null, new HashMap<>());
        if(prevRepoEntry != null) {
            prevEntry.getRepositories().put(prevRepoEntry.getName(), prevRepoEntry);
        }

        ProjectEntry newEntry = new ProjectEntry(null, new HashMap<>());
        if(newRepoEntry != null) {
            newEntry.getRepositories().put(newRepoEntry.getName(), newRepoEntry);
        }

        Map<String, Object> changes = DiffUtils.compare(prevEntry, newEntry);

        auditLog.add(AuditObject.PROJECT, AuditAction.UPDATE)
                .field("orgId", orgId)
                .field("orgName", orgName)
                .field("projectName", projectName)
                .field("projectId", projectId)
                .field("changes", changes)
                .log();
    }
}
