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

import java.util.List;
import java.util.Set;

import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

public class InviteUserStepProviderFactory
        implements WorkflowStepProviderFactory<InviteUserStepProvider> {

    public static final String ID = "invite-user";

    @Override
    public InviteUserStepProvider create(KeycloakSession session, ComponentModel model) {
        return new InviteUserStepProvider(session, model);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Set<ResourceType> getSupportedResourceTypes() {
        return Set.of(ResourceType.USERS);
    }

    @Override
    public String getHelpText() {
        return "Sends an invitation email with a one-time action link to the user";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return ProviderConfigurationBuilder.create()
                .property()
                    .name(InviteUserStepProvider.CONFIG_ACTIONS)
                    .label("Required Actions")
                    .helpText("One or more required action names the user must complete after clicking the invitation link. " +
                            "Supported values: UPDATE_PASSWORD, VERIFY_EMAIL, UPDATE_PROFILE, UPDATE_EMAIL, " +
                            "CONFIGURE_TOTP, TERMS_AND_CONDITIONS. " +
                            "Defaults to UPDATE_PASSWORD and VERIFY_EMAIL when not set.")
                    .type(ProviderConfigProperty.MULTIVALUED_STRING_TYPE)
                    .add()
                .property()
                    .name(InviteUserStepProvider.CONFIG_CLIENT_ID)
                    .label("Client ID")
                    .helpText("The client id to associate with the action token. " +
                            "Defaults to the system client when not set.")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .add()
                .property()
                    .name(InviteUserStepProvider.CONFIG_REDIRECT_URI)
                    .label("Redirect URI")
                    .helpText("The URI to redirect the user to after completing all required actions. " +
                            "Must be a valid redirect URI for the configured client. " +
                            "Defaults to the account console when not set.")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .add()
                .property()
                    .name(InviteUserStepProvider.CONFIG_LIFESPAN)
                    .label("Lifespan")
                    .helpText("Validity duration of the action token link. " +
                            "Accepts ISO-8601 duration (e.g. PT12H) or a number followed by a unit " +
                            "(ms, s, m, h, d). Defaults to the realm's admin action token lifespan.")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .add()
                .build();
    }
}
