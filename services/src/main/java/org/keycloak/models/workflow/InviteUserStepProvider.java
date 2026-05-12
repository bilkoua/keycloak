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

package org.keycloak.models.workflow;

import jakarta.ws.rs.core.UriBuilder;
import org.keycloak.authentication.actiontoken.execactions.ExecuteActionsActionToken;
import org.keycloak.authentication.requiredactions.util.RequiredActionsValidator;
import org.keycloak.common.util.DurationConverter;
import org.keycloak.common.util.Time;
import org.keycloak.component.ComponentModel;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RequiredActionProviderModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.SystemClientUtil;
import org.keycloak.protocol.oidc.utils.RedirectUtils;
import org.keycloak.services.resources.LoginActionsService;
import org.keycloak.utils.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

public class InviteUserStepProvider implements WorkflowStepProvider {

    private final Logger log = Logger.getLogger(InviteUserStepProvider.class);

    static final String CONFIG_ACTIONS = "actions";
    static final String CONFIG_CLIENT_ID = "client-id";
    static final String CONFIG_REDIRECT_URI = "redirect-uri";
    static final String CONFIG_LIFESPAN = "lifespan";

    private static final List<String> DEFAULT_ACTIONS = List.of(
            UserModel.RequiredAction.UPDATE_PASSWORD.name(),
            UserModel.RequiredAction.VERIFY_EMAIL.name()
    );

    private final KeycloakSession session;
    private final ComponentModel stepModel;

    public InviteUserStepProvider(KeycloakSession session, ComponentModel model) {
        this.session = session;
        this.stepModel = model;
    }

    @Override
    public void run(WorkflowExecutionContext context) {
        RealmModel realm = session.getContext().getRealm();

        if (realm == null) {
            log.warnv("No realm found in session context, skipping invite-user step");
            return;
        }

        UserModel user = session.users().getUserById(realm, context.getResourceId());

        if (user == null) {
            log.warnv("User with id {0} not found in realm {1}, skipping invite-user step",
                    context.getResourceId(), realm.getName());
            return;
        }

        if (StringUtil.isBlank(user.getEmail())) {
            log.debugv("Skipping invite-user step: user {0} has no email address", user.getUsername());
            return;
        }

        if (!user.isEnabled()) {
            log.debugv("Skipping invite-user step: user {0} is disabled", user.getUsername());
            return;
        }

        List<String> actions = resolveActions(realm, user);

        if (actions.isEmpty()) {
            log.debugv("No applicable required actions for user {0}, skipping invite-user step",
                    user.getUsername());
            return;
        }

        // Validate that all resolved actions have a registered provider factory
        if (!RequiredActionsValidator.validRequiredActions(session, actions)) {
            log.warnv("Invalid required actions configured: {0}, skipping invite-user step", actions);
            return;
        }

        String clientId = getConfigValue(CONFIG_CLIENT_ID, null);
        String redirectUri = getConfigValue(CONFIG_REDIRECT_URI, null);

        if (redirectUri != null && clientId == null) {
            log.warnv("Redirect URI {0} specified without a client-id, skipping invite-user step", redirectUri);
            return;
        }

        int lifespanSeconds = resolveLifespanSeconds(realm);

        ClientModel client = resolveClient(realm, clientId);

        if (client == null) {
            log.warnv("Client {0} not found or not enabled in realm {1}, skipping invite-user step",
                    clientId, realm.getName());
            return;
        }

        String resolvedRedirectUri = resolveRedirectUri(redirectUri, client);

        if (redirectUri != null && resolvedRedirectUri == null) {
            log.warnv("Invalid redirect URI {0} for client {1}, skipping invite-user step",
                    redirectUri, client.getClientId());
            return;
        }

        sendEmail(realm, user, actions, client.getClientId(), resolvedRedirectUri, lifespanSeconds);
    }

    private List<String> resolveActions(RealmModel realm, UserModel user) {
        List<String> configuredActions = stepModel.getConfig().getOrDefault(CONFIG_ACTIONS, List.of());

        List<String> source = configuredActions.isEmpty() ? DEFAULT_ACTIONS : configuredActions;
        List<String> resolved = new ArrayList<>();

        for (String raw : source) {
            if (StringUtil.isBlank(raw)) {
                continue;
            }

            String normalized = raw.trim().replace("-", "_").toUpperCase();

            // Skip VERIFY_EMAIL when email is already verified
            if (UserModel.RequiredAction.VERIFY_EMAIL.name().equals(normalized) && user.isEmailVerified()) {
                log.debugv("Skipping VERIFY_EMAIL for user {0}: email is already verified", user.getUsername());
                continue;
            }

            // Skip actions that are disabled in the realm
            RequiredActionProviderModel providerModel = realm.getRequiredActionProviderByAlias(normalized);
            if (providerModel != null && !providerModel.isEnabled()) {
                log.warnv("Required action {0} is not enabled in realm {1}, skipping", normalized, realm.getName());
                continue;
            }

            resolved.add(normalized);
        }

        return resolved;
    }

    private int resolveLifespanSeconds(RealmModel realm) {
        String raw = getConfigValue(CONFIG_LIFESPAN, null);

        if (raw != null) {
            try {
                return Math.toIntExact(DurationConverter.parseDuration(raw).toSeconds());
            } catch (Exception e) {
                log.warnv("Invalid lifespan value {0} configured, falling back to realm default", raw);
            }
        }

        return realm.getActionTokenGeneratedByAdminLifespan();
    }

    private ClientModel resolveClient(RealmModel realm, String clientId) {
        if (StringUtil.isBlank(clientId)) {
            return SystemClientUtil.getSystemClient(realm);
        }

        ClientModel client = realm.getClientByClientId(clientId);

        if (client == null) {
            log.debugv("Client {0} not found in realm {1}", clientId, realm.getName());
            return null;
        }

        if (!client.isEnabled()) {
            log.debugv("Client {0} is not enabled in realm {1}", clientId, realm.getName());
            return null;
        }

        return client;
    }

    private String resolveRedirectUri(String redirectUri, ClientModel client) {
        if (StringUtil.isBlank(redirectUri)) {
            return null;
        }

        return RedirectUtils.verifyRedirectUri(session, redirectUri, client);
    }

    private void sendEmail(
            RealmModel realm,
            UserModel user,
            List<String> actions,
            String clientId,
            String redirectUri,
            int lifespanSeconds
    ) {
        int expiration = Time.currentTime() + lifespanSeconds;
        long lifespanMinutes = TimeUnit.SECONDS.toMinutes(lifespanSeconds);

        ExecuteActionsActionToken token = new ExecuteActionsActionToken(
                user.getId(),
                user.getEmail(),
                expiration,
                actions,
                redirectUri,
                clientId
        );

        try {
            UriBuilder builder = LoginActionsService.actionTokenProcessor(session.getContext().getUri());
            builder.queryParam("key", token.serialize(session, realm, session.getContext().getUri()));
            String link = builder.build(realm.getName()).toString();

            session.getProvider(EmailTemplateProvider.class)
                    .setAttribute(Constants.IGNORE_ACCEPT_LANGUAGE_HEADER, true)
                    .setRealm(realm)
                    .setUser(user)
                    .sendUserInviteEmail(link, lifespanMinutes);

            log.debugv("Invite-user email sent to user {0} ({1}) in realm {2} with actions {3}",
                    user.getUsername(), user.getEmail(), realm.getName(), actions);

        } catch (EmailException e) {
            log.errorv(e, "Failed to send invite-user email to user {0} ({1}) in realm {2}",
                    user.getUsername(), user.getEmail(), realm.getName());
        }
    }

    private String getConfigValue(String key, String defaultValue) {
        String value = stepModel.getConfig().getFirst(key);
        return (value != null && !value.isBlank()) ? value.trim() : defaultValue;
    }

    @Override
    public void close() {
    }
}
