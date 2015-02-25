package org.jetbrains.teamcity.aad;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.util.text.StringUtil;
import jetbrains.buildServer.controllers.interceptors.auth.HttpAuthenticationResult;
import jetbrains.buildServer.controllers.interceptors.auth.HttpAuthenticationSchemeAdapter;
import jetbrains.buildServer.serverSide.auth.LoginConfiguration;
import jetbrains.buildServer.serverSide.auth.ServerPrincipal;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import org.apache.commons.codec.binary.Base64;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

/**
 * @author Evgeniy.Koshkin
 */
public class AADAuthenticationScheme extends HttpAuthenticationSchemeAdapter {

  private static final String POST_METHOD = "POST";
  private static final String ID_TOKEN = "id_token";
  private static final String JWT_PARTS_DELIMITER = "\\.";
  private static final String NONCE = "nonce";
  private static final String NAME = "name";
  private static final String OID = "oid"; //object ID
  private static final String OVERVIEW_HTML = "/overview.html";

  @NotNull private final PluginDescriptor myPluginDescriptor;
  @NotNull private final ServerPrincipalFactory myPrincipalFactory;

  public AADAuthenticationScheme(@NotNull final LoginConfiguration loginConfiguration,
                                 @NotNull final PluginDescriptor pluginDescriptor,
                                 @NotNull final ServerPrincipalFactory principalFactory) {
    myPluginDescriptor = pluginDescriptor;
    myPrincipalFactory = principalFactory;
    loginConfiguration.registerAuthModuleType(this);
  }

  @NotNull
  @Override
  protected String doGetName() {
    return AADConstants.AAD_AUTH_SCHEME_NAME;
  }

  @NotNull
  @Override
  public String getDisplayName() {
    return "Microsoft Azure Active Directory";
  }

  @NotNull
  @Override
  public String getDescription() {
    return "Authentication via Microsoft Azure Active Directory";
  }

  @Nullable
  @Override
  public String getEditPropertiesJspFilePath() {
    return myPluginDescriptor.getPluginResourcesPath("editAADSchemeProperties.jsp");
  }

  @Nullable
  @Override
  public Collection<String> validate(@NotNull Map<String, String> properties) {
    final Collection<String> errors = new ArrayList<String>();
    if(StringUtil.isEmptyOrSpaces(properties.get(AADConstants.AUTH_ENDPOINT_SCHEME_PROPERTY_KEY))){
      errors.add("App OAuth 2.0 authorization endpoint should be specified.");
    }
    if(StringUtil.isEmptyOrSpaces(properties.get(AADConstants.CLIENT_ID_SCHEME_PROPERTY_KEY))){
      errors.add("Client ID should be specified.");
    }
    return errors.isEmpty() ? super.validate(properties) : errors;
  }

  @NotNull
  @Override
  public HttpAuthenticationResult processAuthenticationRequest(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Map<String, String> schemeProperties) throws IOException {
    if (!request.getMethod().equals(POST_METHOD)) return HttpAuthenticationResult.notApplicable();

    final String idToken = request.getParameter(ID_TOKEN);
    if(idToken == null) return HttpAuthenticationResult.notApplicable();

    final String[] jwtParts = idToken.split(JWT_PARTS_DELIMITER);
    final JsonObject jwtPayload = new JsonParser().parse(new String(Base64.decodeBase64(jwtParts[1].getBytes()))).getAsJsonObject();

    final JsonElement nonce = jwtPayload.get(NONCE);
    final JsonElement name = jwtPayload.get(NAME);
    final JsonElement oid = jwtPayload.get(OID);

    if (nonce == null || name == null || oid == null) return HttpAuthenticationResult.unauthenticated();
    if(!nonce.getAsString().equals(SessionUtil.getSessionId(request))) return HttpAuthenticationResult.unauthenticated();

    final ServerPrincipal principal = myPrincipalFactory.getServerPrincipal(name.getAsString(), oid.getAsString(), schemeProperties);
    return HttpAuthenticationResult.authenticated(principal, true).withRedirect(getUrlForRedirect(request));
  }

  @NotNull
  private static String getUrlForRedirect(@NotNull final HttpServletRequest request) {
    final String url = SessionUtil.readAndForgetInitialRequestUrl(request);
    return url != null ? url : request.getContextPath() + OVERVIEW_HTML;
  }
}