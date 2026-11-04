/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.identity.webapp.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.authentication.pt.PhysicalTenantCamundaGroups;
import io.camunda.security.api.model.authz.AuthorizationResourceType;
import io.camunda.security.api.model.authz.DefaultRole;
import io.camunda.security.api.model.config.AuthenticationMethod;
import io.camunda.security.spring.CamundaSecurityLibraryProperties;
import io.camunda.spring.utils.PhysicalTenantContext;
import io.camunda.zeebe.gateway.rest.config.WebappConfiguration;
import io.camunda.zeebe.gateway.rest.controller.usermanagement.MappingRuleMembershipPolicy;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class AdminClientConfigController {
  private static final Logger LOG = LoggerFactory.getLogger(AdminClientConfigController.class);

  private static final String IS_OIDC = "isOidc";
  private static final String IS_CAMUNDA_GROUPS_ENABLED = "isCamundaGroupsEnabled";
  private static final String IS_TENANTS_API_ENABLED = "isTenantsApiEnabled";
  private static final String IS_ADDITIONAL_IDP_CONFIGURED = "isAdditionalIdpConfigured";
  private static final String ORGANIZATION_ID = "organizationId";
  private static final String CLUSTER_ID = "clusterId";
  private static final String ID_PATTERN = "idPattern";
  private static final String RESOURCE_PERMISSIONS = "resourcePermissions";
  private static final String DEFAULT_ROLE_IDS = "defaultRoleIds";
  private static final String IS_NEW_DESIGN_SYSTEM_ENABLED = "isNewDesignSystemEnabled";
  private static final String FALLBACK_CONFIG_JS = "window.clientConfig = {};";
  private static final String CONFIG_JS_TEMPLATE = "window.clientConfig = %s;";

  private final CamundaSecurityLibraryProperties cslProperties;
  private final PhysicalTenantCamundaGroups camundaGroups;
  private final MappingRuleMembershipPolicy mappingRuleMembershipPolicy;
  private final boolean isNewDesignSystemEnabled;
  // Only the groups mode and the additional IdP are resolved per physical tenant, so the script is
  // built once for each combination of the two.
  private final Map<TenantClientConfig, String> clientConfigs = new ConcurrentHashMap<>();
  private final ObjectMapper objectMapper;

  public AdminClientConfigController(
      final CamundaSecurityLibraryProperties cslProperties,
      @Autowired(required = false) final WebappConfiguration webappConfiguration,
      final Environment environment) {
    this.cslProperties = cslProperties;
    camundaGroups = new PhysicalTenantCamundaGroups(environment);
    mappingRuleMembershipPolicy = MappingRuleMembershipPolicy.of(cslProperties, environment);
    final var resolvedWebappConfiguration =
        webappConfiguration != null ? webappConfiguration : new WebappConfiguration();
    isNewDesignSystemEnabled = resolvedWebappConfiguration.resolveNewDesignSystemEnabled();

    LOG.info(
        "Admin new design system resolved to {} (explicit override={})",
        isNewDesignSystemEnabled,
        resolvedWebappConfiguration.getNewDesignSystemEnabled());

    objectMapper = new ObjectMapper();
  }

  private String generateClientConfig(
      final CamundaSecurityLibraryProperties cslProperties,
      final boolean isNewDesignSystemEnabled,
      final boolean isAdditionalIdpConfigured,
      final boolean isCamundaGroupsEnabled) {
    try {
      final Map<String, Object> config =
          createConfigMap(
              cslProperties,
              isNewDesignSystemEnabled,
              isAdditionalIdpConfigured,
              isCamundaGroupsEnabled);
      final String configJson = objectMapper.writeValueAsString(config);
      return String.format(CONFIG_JS_TEMPLATE, configJson);
    } catch (final JsonProcessingException e) {
      LOG.warn("Error serializing client config to JSON", e);
      return FALLBACK_CONFIG_JS;
    }
  }

  private Map<String, Object> createConfigMap(
      final CamundaSecurityLibraryProperties cslProperties,
      final boolean isNewDesignSystemEnabled,
      final boolean isAdditionalIdpConfigured,
      final boolean isCamundaGroupsEnabled) {
    final var config = new java.util.HashMap<String, Object>();
    final var saasConfiguration = cslProperties.getSaas();

    config.put(IS_OIDC, String.valueOf(isOidcAuthentication(cslProperties)));
    config.put(IS_CAMUNDA_GROUPS_ENABLED, String.valueOf(isCamundaGroupsEnabled));
    config.put(
        IS_TENANTS_API_ENABLED, String.valueOf(cslProperties.getMultiTenancy().isApiEnabled()));
    config.put(IS_ADDITIONAL_IDP_CONFIGURED, String.valueOf(isAdditionalIdpConfigured));
    config.put(ORGANIZATION_ID, saasConfiguration.getOrganizationId());
    config.put(CLUSTER_ID, saasConfiguration.getClusterId());
    config.put(ID_PATTERN, cslProperties.getIdValidationPattern());
    config.put(RESOURCE_PERMISSIONS, AuthorizationResourceType.buildResourcePermissionsMap());
    config.put(DEFAULT_ROLE_IDS, DefaultRole.ids());
    config.put(IS_NEW_DESIGN_SYSTEM_ENABLED, String.valueOf(isNewDesignSystemEnabled));

    return config;
  }

  private boolean isOidcAuthentication(final CamundaSecurityLibraryProperties cslProperties) {
    return AuthenticationMethod.OIDC.equals(cslProperties.getAuthentication().getMethod());
  }

  @GetMapping(path = "/admin/config.js", produces = "text/javascript;charset=UTF-8")
  @ResponseBody
  @Hidden
  public String getClientConfig(final HttpServletRequest request) {
    final String physicalTenantId = PhysicalTenantContext.getPhysicalTenantId(request);
    final var tenantClientConfig =
        new TenantClientConfig(
            camundaGroups.isCamundaGroupsEnabled(
                physicalTenantId, cslProperties.getAuthentication()),
            mappingRuleMembershipPolicy.isAdditionalIdpConfigured(physicalTenantId));
    return clientConfigs.computeIfAbsent(
        tenantClientConfig,
        key ->
            generateClientConfig(
                cslProperties,
                isNewDesignSystemEnabled,
                key.isAdditionalIdpConfigured(),
                key.isCamundaGroupsEnabled()));
  }

  private record TenantClientConfig(
      boolean isCamundaGroupsEnabled, boolean isAdditionalIdpConfigured) {}
}
