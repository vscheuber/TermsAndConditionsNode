/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2017-2018 ForgeRock AS.
 */


package org.forgerock.openam.auth.nodes;

import static org.forgerock.openam.auth.node.api.SharedStateConstants.USERNAME;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.ResourceBundle;

import javax.inject.Inject;
import javax.security.auth.callback.ConfirmationCallback;
import javax.security.auth.callback.TextOutputCallback;

import org.forgerock.json.JsonValue;
import org.forgerock.openam.annotations.sm.Attribute;
import org.forgerock.openam.auth.node.api.Action;
import org.forgerock.openam.auth.node.api.Node;
import org.forgerock.openam.auth.node.api.NodeProcessException;
import org.forgerock.openam.auth.node.api.TreeContext;
import org.forgerock.openam.sm.annotations.adapters.Password;
import org.forgerock.util.i18n.PreferredLocales;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.inject.assistedinject.Assisted;
import com.sun.identity.authentication.callbacks.ScriptTextOutputCallback;
import com.sun.identity.sm.RequiredValueValidator;

@Node.Metadata(outcomeProvider = TermsAndConditionsNode.TermsAndConditionsNodeOutcomeProvider.class,
        configClass = TermsAndConditionsNode.Config.class)
public class TermsAndConditionsNode implements Node {

    private static final String BUNDLE = TermsAndConditionsNode.class.getName().replace(".", "/");
    private final Logger logger = LoggerFactory.getLogger("amAuth");
    private final Config config;

    /**
     * Configuration for the node.
     * It can have as many attributes as needed, or none.
     */
    public interface Config {

        @Attribute(order = 100, validators = {RequiredValueValidator.class})
        String idmBaseUrl();

        @Attribute(order = 200, validators = {RequiredValueValidator.class})
        default String idmAdminUser() { return "openidm-admin"; }

        @Attribute(order = 300, validators = {RequiredValueValidator.class})
        @Password
        char[] idmAdminPassword();
        
    }


    /*
     * Constructs a new TermsAndConditionsNode instance.
     * We can have Assisted:
     * * Config config
     * * UUID nodeId
     *
     * We may want to Inject:
     * CoreWrapper
     */
    @Inject
    public TermsAndConditionsNode(@Assisted Config config) {
    	this.config = config;
    }

    @Override
    public Action process(TreeContext context) throws NodeProcessException {
        JsonValue sharedState = context.sharedState;
        JsonValue transientState = context.transientState;

        if (context.getCallback(ConfirmationCallback.class).isPresent()) {
            ConfirmationCallback confirmationCallback = context.getCallback(ConfirmationCallback.class).get();
            if (confirmationCallback.getSelectedIndex() == 0) {
            	logger.debug("Accepted.");
            	
            	acceptRequirements(sharedState.get(USERNAME).asString());
            	
                return Action.goTo(TermsAndConditionsOutcome.ACCEPTED.name()).replaceSharedState(sharedState).replaceTransientState(transientState).build();
            }
            logger.debug("Canceled.");
            return Action.goTo(TermsAndConditionsOutcome.CANCELED.name()).replaceSharedState(sharedState).replaceTransientState(transientState).build();
        }

        JSONObject requirements = getRequirements(sharedState.get(USERNAME).asString());
        if (null!=requirements && requirements.has("terms")) {
        	logger.debug("Need to accept terms:" + requirements);
        	try {
				String terms = requirements.getString("terms");
	        	String title = requirements.getJSONObject("uiConfig").getString("displayName");
	        	String message = requirements.getJSONObject("uiConfig").getString("purpose");
	        	String confirm = requirements.getJSONObject("uiConfig").getString("buttonText");
	        	
	        	String clientSideScriptExecutorFunction = createClientSideScriptExecutorFunction(getScript(), terms, "TermsAndConditions");
	            ScriptTextOutputCallback scriptAndSelfSubmitCallback =
	                    new ScriptTextOutputCallback(clientSideScriptExecutorFunction);
	        	
	            return Action.send(Arrays.asList(scriptAndSelfSubmitCallback,
	            								 new TextOutputCallback(TextOutputCallback.INFORMATION, title),
	            								 new TextOutputCallback(TextOutputCallback.INFORMATION, message),
	            								 new TextOutputCallback(TextOutputCallback.INFORMATION, terms),
	                                             new ConfirmationCallback(ConfirmationCallback.INFORMATION, new String[]{confirm, "Cancel"}, 0))).build();
			} catch (JSONException e) {
				logger.debug("process: ", e);
			}
        }
        
        logger.debug("Nothing to do.");
        return Action.goTo(TermsAndConditionsOutcome.CONTINUE.name()).replaceSharedState(sharedState).replaceTransientState(transientState).build();

    }

	private String getScript() {
		// client-side script to change the look and feel of how the terms and conditions are displayed.
        StringBuffer script = new StringBuffer();
        script.append("var callbackScript = document.createElement(\"script\");\n");
        script.append("callbackScript.type = \"text/javascript\";\n");
        script.append("callbackScript.text = \"function completed() { document.querySelector(\\\"input[type=submit]\\\").click(); }\";\n");
        script.append("document.body.appendChild(callbackScript);\n");
        script.append("\n");
        script.append("submitted = true;\n");
        script.append("\n");
		script.append("var decodeHTML = function (html) {\n");
		script.append("	var txt = document.createElement('textarea');\n");
		script.append("	txt.innerHTML = html;\n");
		script.append("	return txt.value;\n");
		script.append("};");
		script.append("\n");
		script.append("function callback() {\n");
		script.append("\n");
		script.append("    var title = document.getElementById('callback_1');\n");
		script.append("    title.className = \"0 h1\";\n");
		script.append("    title.align = \"center\";\n");
		script.append("\n");
		script.append("    var message = document.getElementById('callback_2');\n");
		script.append("    message.className = \"0 h3\";\n");
		script.append("    message.align = \"center\";\n");
		script.append("\n");
		script.append("    var terms = document.getElementById('callback_3');\n");
		script.append("    terms.className = \"form-control  pre-scrollable\";\n");
		script.append("    terms.style = \"height: 150px;\";\n");
		script.append("    terms.innerHTML = decodeHTML(terms.innerHTML);\n");
		script.append("}\n");
		script.append("\n");
		script.append("if (document.readyState !== 'loading') {\n");
		script.append("  callback();\n");
		script.append("} else {\n");
		script.append("  document.addEventListener(\"DOMContentLoaded\", callback);\n");
		script.append("}");
		return script.toString();
	}

    public static String createClientSideScriptExecutorFunction(String script, String terms, String outputParameterId) {
        return String.format(
                "(function(output) {\n" +
                "    var autoSubmitDelay = 0,\n" +
                "        submitted = false;\n" +
                "    function submit() {\n" +
                "        if (submitted) {\n" +
                "            return;\n" +
                "        }" +
                "        document.forms[0].submit();\n" +
                "        submitted = true;\n" +
                "    }\n" +
                "    %s\n" + // script
                "    setTimeout(submit, autoSubmitDelay);\n" +
                "}) (document.forms[0].elements['%s']);\n", // outputParameterId
                script,
                outputParameterId);
    }

    /**
     * The possible outcomes for the LdapDecisionNode.
     */
    public enum TermsAndConditionsOutcome {
        /**
         * Nothing to do.
         */
        CONTINUE,
        /**
         * Accepted terms.
         */
        ACCEPTED,
        /**
         * Canceled.
         */
        CANCELED
    }


    /**
     * Defines the possible outcomes from this Login node.
     */
    public static class TermsAndConditionsNodeOutcomeProvider implements org.forgerock.openam.auth.node.api.OutcomeProvider {
        @Override
        public List<Outcome> getOutcomes(PreferredLocales locales, JsonValue nodeAttributes) {
            ResourceBundle bundle = locales.getBundleInPreferredLocale(TermsAndConditionsNode.BUNDLE,
                                                                       TermsAndConditionsNodeOutcomeProvider.class.getClassLoader());
            return ImmutableList.of(
                    new Outcome(TermsAndConditionsOutcome.CONTINUE.name(), bundle.getString("okOutcome")),
                    new Outcome(TermsAndConditionsOutcome.ACCEPTED.name(), bundle.getString("acceptOutcome")),
                    new Outcome(TermsAndConditionsOutcome.CANCELED.name(), bundle.getString("cancelOutcome")));
        }
    }
    
    private JSONObject getRequirements(String username) {
    	String idmBaseUrl = config.idmBaseUrl();
    	String idmAdminUser = config.idmAdminUser();
    	String idmAdminPassword = new String(config.idmAdminPassword());
    	String idmTermsAndConditionsUrl = String.format("%s/selfservice/termsAndConditions?_prettyPrint=true", idmBaseUrl);
        try {
            URL url = new URL(idmTermsAndConditionsUrl);
            logger.debug("url = " + url);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("content-type", "application/json");
            conn.setRequestProperty("X-OpenIDM-Username", idmAdminUser);
            conn.setRequestProperty("X-OpenIDM-Password", idmAdminPassword);
            conn.setRequestProperty("X-OpenIDM-RunAs", username);
            conn.setRequestProperty("User-Agent", "ForgeRock TermsAndConditions Authentication Node");

            // handle response
            String response = "";
            BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));
            String output;
            while ((output = br.readLine()) != null) {
                response = response + output;
            }
            br.close();
            // end handle response
            
            int responseCode = conn.getResponseCode();
            if ( responseCode == 200 ) {
            	logger.debug("getRequirements: HTTP Success: response code - {}, response: {}", responseCode, response);
                
                conn.disconnect();
                return new JSONObject(response).getJSONObject("requirements");
            }
            else {
            	String responseMessage = conn.getResponseMessage();
            	logger.debug("getRequirements: HTTP failed, response code: {} - {}, response: {}", responseCode, responseMessage, response);
                
                conn.disconnect();
                return null;
            }
        } catch (Throwable t) {
        	logger.debug("getRequirements: ", t);
        }
        return null;
    }
    
    private boolean acceptRequirements(String username) {
    	String idmBaseUrl = config.idmBaseUrl();
    	String idmAdminUser = config.idmAdminUser();
    	String idmAdminPassword = new String(config.idmAdminPassword());
    	String idmTermsAndConditionsUrl = String.format("%s/selfservice/termsAndConditions?_action=submitRequirements&_prettyPrint=true", idmBaseUrl);
        try {
            URL url = new URL(idmTermsAndConditionsUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("content-type", "application/json");
            conn.setRequestProperty("X-OpenIDM-Username", idmAdminUser);
            conn.setRequestProperty("X-OpenIDM-Password", idmAdminPassword);
            conn.setRequestProperty("X-OpenIDM-RunAs", username);
            conn.setRequestProperty("User-Agent", "ForgeRock TermsAndConditions Authentication Node");
            
            // handle payload
            conn.setDoOutput(true);
            String jsonPayload = "{\"input\":{\"accept\":\"true\"}}";
            OutputStream os = conn.getOutputStream();
            byte[] payload = jsonPayload.getBytes("utf-8");
            os.write(payload, 0, payload.length);
    		os.flush();
    		os.close();
    		// end handle payload

            // handle response
            String response = "";
            BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));
            String output;
            while ((output = br.readLine()) != null) {
                response = response + output;
            }
            br.close();
            // end handle response
            
    		int responseCode = conn.getResponseCode();
            if ( responseCode == 200 ) {
            	logger.debug("acceptRequirements: HTTP Success: response code - {}, response: {}", responseCode, response);
                
                conn.disconnect();
                return true;
            }
            else {
            	String responseMessage = conn.getResponseMessage();
            	logger.debug("acceptRequirements: HTTP failed, response code: {} - {}, response: {}", responseCode, responseMessage, response);
                
                conn.disconnect();
                return false;
            }
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

}
