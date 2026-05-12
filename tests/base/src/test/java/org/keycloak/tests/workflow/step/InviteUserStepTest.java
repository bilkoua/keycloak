/*
 * Copyright 2025 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.tests.workflow.step;

import java.time.Duration;

import jakarta.mail.internet.MimeMessage;

import org.keycloak.models.UserModel;
import org.keycloak.models.workflow.InviteUserStepProvider;
import org.keycloak.models.workflow.InviteUserStepProviderFactory;
import org.keycloak.models.workflow.events.UserCreatedWorkflowEventFactory;
import org.keycloak.representations.workflows.WorkflowRepresentation;
import org.keycloak.representations.workflows.WorkflowStepRepresentation;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.mail.MailServer;
import org.keycloak.testframework.mail.annotations.InjectMailServer;
import org.keycloak.testframework.realm.UserBuilder;
import org.keycloak.tests.workflow.AbstractWorkflowTest;
import org.keycloak.tests.workflow.config.WorkflowsBlockingServerConfig;
import org.keycloak.tests.workflow.util.EmailTestUtils;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests the execution of the 'invite-user' workflow step.
 */
@KeycloakIntegrationTest(config = WorkflowsBlockingServerConfig.class)
public class InviteUserStepTest extends AbstractWorkflowTest {

    @InjectMailServer
    private MailServer mailServer;

    @BeforeEach
    void cleanupMailServer() {
        mailServer.runCleanup();
    }

    @Test
    public void testEmailSentWithDefaultActionsOnUserCreation() {
        // Create workflow: invite user on creation with default actions
        managedRealm.admin().workflows().create(
                WorkflowRepresentation.withName("onboarding")
                        .onEvent(UserCreatedWorkflowEventFactory.ID)
                        .withSteps(
                                WorkflowStepRepresentation.create()
                                        .of(InviteUserStepProviderFactory.ID)
                                        .build()
                        ).build()
        ).close();

        // Create user with email to trigger the workflow
        managedRealm.admin().users().create(
                UserBuilder.create().username("newuser").email("newuser@example.com").build()
        ).close();

        // Verify invite email was sent to the created user
        Awaitility.await()
                .timeout(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    MimeMessage message = EmailTestUtils.findEmailByRecipient(mailServer, "newuser@example.com");
                    assertNotNull(message, "Expected invite email to be sent to newuser@example.com");
                });
    }

    @Test
    public void testEmailSentWithExplicitActions() {
        // Create workflow: invite user with explicit UPDATE_PASSWORD action only
        managedRealm.admin().workflows().create(
                WorkflowRepresentation.withName("onboarding-password-only")
                        .onEvent(UserCreatedWorkflowEventFactory.ID)
                        .withSteps(
                                WorkflowStepRepresentation.create()
                                        .of(InviteUserStepProviderFactory.ID)
                                        .withConfig(InviteUserStepProvider.CONFIG_ACTIONS,
                                                UserModel.RequiredAction.UPDATE_PASSWORD.name())
                                        .build()
                        ).build()
        ).close();

        // Create user with email to trigger the workflow
        managedRealm.admin().users().create(
                UserBuilder.create().username("pwduser").email("pwduser@example.com").build()
        ).close();

        // Verify invite email was sent to the created user
        Awaitility.await()
                .timeout(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    MimeMessage message = EmailTestUtils.findEmailByRecipient(mailServer, "pwduser@example.com");
                    assertNotNull(message, "Expected invite email to be sent to pwduser@example.com");
                });
    }

    @Test
    public void testEmailSkippedWhenUserHasNoEmail() {
        // Create workflow: invite user on creation
        managedRealm.admin().workflows().create(
                WorkflowRepresentation.withName("onboarding-no-email")
                        .onEvent(UserCreatedWorkflowEventFactory.ID)
                        .withSteps(
                                WorkflowStepRepresentation.create()
                                        .of(InviteUserStepProviderFactory.ID)
                                        .build()
                        ).build()
        ).close();

        // Create user without email address, workflow runs synchronously, no email should be sent
        managedRealm.admin().users().create(
                UserBuilder.create().username("noemailuser").build()).close();

        // Verify no email was sent for user without email address
        assertNull(EmailTestUtils.findEmailByRecipientContaining(mailServer, "noemailuser"),
                "Expected no email to be sent for user without email address");
    }

    @Test
    public void testEmailSkippedWhenAllActionsDisabledInRealm() {
        // Create workflow: invite user requesting TERMS_AND_CONDITIONS which is disabled by default
        managedRealm.admin().workflows().create(
                WorkflowRepresentation.withName("onboarding-disabled-actions")
                        .onEvent(UserCreatedWorkflowEventFactory.ID)
                        .withSteps(
                                WorkflowStepRepresentation.create()
                                        .of(InviteUserStepProviderFactory.ID)
                                        .withConfig(InviteUserStepProvider.CONFIG_ACTIONS,
                                                UserModel.RequiredAction.TERMS_AND_CONDITIONS.name())
                                        .build()
                        ).build()
        ).close();

        // Create user with email, TERMS_AND_CONDITIONS is disabled so no invite should be sent
        managedRealm.admin().users().create(
                UserBuilder.create().username("disabledactionuser").email("disabledactionuser@example.com").build()
        ).close();

        // Verify no email was sent since all configured actions are disabled in realm
        assertNull(EmailTestUtils.findEmailByRecipientContaining(mailServer, "disabledactionuser"),
                "Expected no email when all configured actions are disabled in realm");
    }

    @Test
    public void testEmailSkippedWhenRedirectUriSpecifiedWithoutClientId() {
        // Create workflow: invite user with redirect-uri but no client-id
        managedRealm.admin().workflows().create(
                WorkflowRepresentation.withName("onboarding-redirect-no-client")
                        .onEvent(UserCreatedWorkflowEventFactory.ID)
                        .withSteps(
                                WorkflowStepRepresentation.create()
                                        .of(InviteUserStepProviderFactory.ID)
                                        .withConfig(InviteUserStepProvider.CONFIG_REDIRECT_URI,
                                                "https://app.example.com/welcome")
                                        .build()
                        ).build()
        ).close();

        // Create user with email, step should be skipped due to missing client-id
        managedRealm.admin().users().create(
                UserBuilder.create().username("redirectuser").email("redirectuser@example.com").build()
        ).close();

        // Verify no email was sent since redirect-uri requires client-id
        assertNull(EmailTestUtils.findEmailByRecipientContaining(mailServer, "redirectuser"),
                "Expected no email when redirect-uri is specified without client-id");
    }

    @Test
    public void testVerifyEmailSkippedWhenAlreadyVerified() {
        // Create workflow: invite user requesting UPDATE_PASSWORD only
        managedRealm.admin().workflows().create(
                WorkflowRepresentation.withName("onboarding-verified")
                        .onEvent(UserCreatedWorkflowEventFactory.ID)
                        .withSteps(
                                WorkflowStepRepresentation.create()
                                        .of(InviteUserStepProviderFactory.ID)
                                        .withConfig(InviteUserStepProvider.CONFIG_ACTIONS,
                                                UserModel.RequiredAction.UPDATE_PASSWORD.name())
                                        .build()
                        ).build()
        ).close();

        // Create user with pre-verified email
        managedRealm.admin().users().create(
                UserBuilder.create()
                        .username("verifieduser")
                        .email("verifieduser@example.com")
                        .emailVerified(true)
                        .build()
        ).close();

        // Verify invite email was still sent (UPDATE_PASSWORD action is still required)
        Awaitility.await()
                .timeout(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    MimeMessage message = EmailTestUtils.findEmailByRecipient(mailServer, "verifieduser@example.com");
                    assertNotNull(message, "Expected invite email to be sent even when email is already verified");
                });
    }

    @Test
    public void testEachUserReceivesExactlyOneEmail() {
        // Create workflow: invite user on creation
        managedRealm.admin().workflows().create(
                WorkflowRepresentation.withName("onboarding-once")
                        .onEvent(UserCreatedWorkflowEventFactory.ID)
                        .withSteps(
                                WorkflowStepRepresentation.create()
                                        .of(InviteUserStepProviderFactory.ID)
                                        .build()
                        ).build()
        ).close();

        // Create two separate users
        managedRealm.admin().users().create(
                UserBuilder.create().username("user-alpha").email("user-alpha@example.com").build()
        ).close();
        managedRealm.admin().users().create(
                UserBuilder.create().username("user-beta").email("user-beta@example.com").build()
        ).close();

        // Verify each user received exactly one invite email
        Awaitility.await()
                .timeout(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    assertThat(EmailTestUtils.findEmailsByRecipient(mailServer, "user-alpha@example.com"), hasSize(1));
                    assertThat(EmailTestUtils.findEmailsByRecipient(mailServer, "user-beta@example.com"), hasSize(1));
                });
    }

    @Test
    public void testInviteEmailSubjectContainsRealmName() {
        // Create workflow: invite user on creation
        managedRealm.admin().workflows().create(
                WorkflowRepresentation.withName("onboarding-subject")
                        .onEvent(UserCreatedWorkflowEventFactory.ID)
                        .withSteps(
                                WorkflowStepRepresentation.create()
                                        .of(InviteUserStepProviderFactory.ID)
                                        .build()
                        ).build()
        ).close();

        // Create user with email to trigger the workflow
        managedRealm.admin().users().create(
                UserBuilder.create().username("subjectuser").email("subjectuser@example.com").build()
        ).close();

        // Verify invite email subject contains the realm name
        Awaitility.await()
                .timeout(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    MimeMessage message = EmailTestUtils.findEmailByRecipient(mailServer, "subjectuser@example.com");
                    assertNotNull(message, "Expected invite email to be sent to subjectuser@example.com");
                    assertThat(message.getSubject(), containsString("Welcome to"));
                });
    }
}
