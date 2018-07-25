/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * Copyright (c) 2009-2010 Oracle and/or its affiliates. All rights reserved.
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License"). You
 * may not use this file except in compliance with the License. You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt. See the License for the specific
 * language governing permissions and limitations under the License.
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license." If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above. However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.glassfish.embeddable.CommandResult;
import org.glassfish.embeddable.CommandResult.ExitStatus;
import org.glassfish.embeddable.GlassFish;
import org.glassfish.embeddable.GlassFishException;
import org.glassfish.embeddable.GlassFishProperties;
import org.glassfish.embeddable.GlassFishRuntime;
import org.glassfish.embeddable.archive.ScatteredArchive;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class GlassfishSsoTest {

	private static final String APP1 = "app1";
	private static final String APP2 = "app2";

	/**
	 * This value must be in sync with the glassfish-web.xml.
	 * The default value is 30 minutes (1800 seconds).
	 */
	private static final int SESSION_TIMEOUT_SECONDS = 20;

	/**
	 * The default value is 1 minute (60 seconds).
	 */
	private static final int SESSION_REAP_INTERVAL_SECONDS = 2;

	/**
	 * Specifies the time after which a user’s single sign-on record becomes eligible for purging if no client activity is received.
	 * Since single sign-on applies across several applications on the same virtual server, access to any of the applications keeps the
	 * single sign-on record active.
	 * The default value is 5 minutes (300 seconds).
	 * Higher values provide longer single sign-on persistence for the users at the expense of more memory use on the server.
	 */
	private static final int SSO_MAX_INACTIVE_SECONDS = 10;

	/**
	 * Specifies the interval between purges of expired single sign-on records. The default value is 60.
	 */
	private static final int SSO_REAP_INTERVAL_SECONDS = 2;

	private static GlassFishRuntime runtime;
	private static GlassFish glassfish;

	/**
	 * Logs in on app1 and then uses app2 for a while
	 */
	@Test
	public void testStartingWithApp1() throws Exception {
		System.out.println("== Starting with app1 ==");
		test(c -> simulateUserInteraction(c, false));
	}

	/**
	 * Logs in on app1 and then uses app2 for a while.
	 * Initially app2 is called so it has an active session before login.
	 */
	@Test
	public void testTriggerApp2First() throws Exception {
		System.out.println("== Starting with app2 ==");
		test(c -> simulateUserInteraction(c, true));
	}

	/**
	 * Performs Login on app1 and then uses app2 for a while
	 * 
	 * @param c
	 * @param initiallyCallApp2
	 *            if app2 is called before login
	 */
	private void simulateUserInteraction(WebClient c, boolean initiallyCallApp2) {
		try {
			if (initiallyCallApp2) {
				callServlet(c, APP2, null);
			}

			callServlet(c, APP1, true);

			int expectedTimeoutseconds =
				SESSION_TIMEOUT_SECONDS + SESSION_REAP_INTERVAL_SECONDS + SSO_MAX_INACTIVE_SECONDS + SSO_REAP_INTERVAL_SECONDS;

			int callIntervalMillies = SESSION_TIMEOUT_SECONDS * 1000 / 2;

			int requiredApp2Calls = (expectedTimeoutseconds + SESSION_TIMEOUT_SECONDS) * 1000 / callIntervalMillies;

			System.out.println(requiredApp2Calls + " calls to " + APP2 + " will be performed. Interval: " + (callIntervalMillies / 1000) + "s");

			for (int i = 0; i < requiredApp2Calls; i++) {
				System.out.print((i + 1) + ". ");
				callServlet(c, APP2, false);
				Thread.sleep(callIntervalMillies);
			}

		} catch (IOException | InterruptedException e) {
			throw new RuntimeException(e.getMessage(), e);
		}
	};

	public void test(Consumer<WebClient> consumer) throws Exception {
		try (WebClient webClient = new WebClient()) {
			consumer.accept(webClient);
		}
	}

	private Page callServlet(WebClient webClient, String app, Boolean loginRequired) throws IOException, MalformedURLException {

		System.out.println("Calling " + app);
		Page page = webClient.getPage("http://localhost:8882/" + app + "/hello");

		if (loginRequired == null) {
			//login form may be returned but we do not log in
			System.out.println("Skipping login");
		} else if (loginRequired) {
			page = performLogin(page);
		} else {
			assertFalse(app + " returned Login Page", page.isHtmlPage());
			Assert.assertTrue(
				"Servlet returned wrong content: " + page.getWebResponse().getContentAsString(),
				page.getWebResponse().getContentAsString().startsWith("Hello World from " + app + "!"));
		}

		return page;
	}

	private Page performLogin(Page page) {

		assertTrue(page.isHtmlPage());

		HtmlPage html = (HtmlPage) page;

		HtmlForm form = html.getForms().iterator().next();

		form.getInputByName("j_username").setValueAttribute("user");
		form.getInputByName("j_password").setValueAttribute("changeit");
		try {
			System.out.println("Performing Login");
			Page result = form.getButtonByName("submit").click();

			if (result.isHtmlPage()) {
				fail("Login failed? " + result.getWebResponse().getContentAsString());
			}

			return result;

		} catch (ElementNotFoundException | IOException e) {
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	private static void addUserToRealm(GlassFish glassfish) throws GlassFishException {
		runCommand(
			glassfish,
			"create-file-user",
			"--user",
			"user",
			"--groups",
			"users",
			"--passwordfile=" + new File("password.txt").getAbsolutePath(),
			"user");
	}

	private static void configSso(GlassFish glassfish) throws GlassFishException {
		runCommand(glassfish, "set", "server-config.http-service.sso-enabled=true");

		if (SESSION_REAP_INTERVAL_SECONDS != 60) {
			runCommand(
				glassfish,
				"set",
				"server.web-container.session-config.session-manager.manager-properties.reap-interval-in-seconds=" + SESSION_REAP_INTERVAL_SECONDS);
		}

		if (SSO_MAX_INACTIVE_SECONDS != 300) {
			runCommand(
				glassfish,
				"set",
				"server-config.http-service.virtual-server.server.property.sso-max-inactive-seconds=" + SSO_MAX_INACTIVE_SECONDS);
		}
		if (SSO_REAP_INTERVAL_SECONDS != 60) {
			runCommand(
				glassfish,
				"set",
				"server-config.http-service.virtual-server.server.property.sso-reap-interval-seconds=" + SSO_REAP_INTERVAL_SECONDS);
		}
	}

	private static void undeployApps(GlassFish glassfish, String... apps) throws GlassFishException {
		for (String app : apps) {
			glassfish.getDeployer().undeploy(app);
		}
	}

	private static void deployWar(GlassFish glassfish, String appName) throws IOException, GlassFishException {
		ScatteredArchive war = createWar(appName);
		String deployedName = glassfish.getDeployer().deploy(war.toURI());
		assertEquals(appName, deployedName);
	}

	private static void runCommand(GlassFish glassfish, String var1, String... var2) throws GlassFishException {
		CommandResult result = glassfish.getCommandRunner().run(var1, var2);
		if (result.getExitStatus() != ExitStatus.SUCCESS) {
			System.err.println(var1 + " " + Arrays.stream(var2).collect(Collectors.joining(" ")) + " => " + result.getOutput());
			Optional.ofNullable(result.getFailureCause()).ifPresent(Throwable::printStackTrace);
		} else {
			System.out.println(var1 + " " + Arrays.stream(var2).collect(Collectors.joining(" ")));
		}
	}

	private static ScatteredArchive createWar(String name) throws IOException {
		File f = new File(".");
		f = new File(f, "target");
		f = new File(f, "classes");
		ScatteredArchive war = new ScatteredArchive(name, ScatteredArchive.Type.WAR, f);
		war.addClassPath(f);
		return war;
	}

	@BeforeClass
	public static void startup() throws GlassFishException, IOException {
		runtime = GlassFishRuntime.bootstrap();

		GlassFishProperties glassfishProperties = new GlassFishProperties();
		glassfishProperties.setPort("http-listener", 8882);

		glassfish = runtime.newGlassFish(glassfishProperties);

		// Set the log levels. For example, set 'deployment' and 'server' log levels to FINEST
		//		Logger.getLogger("").getHandlers()[0].setLevel(Level.FINEST);
		//		Logger.getLogger("javax.enterprise.system.tools.deployment").setLevel(Level.FINEST);
		//		Logger.getLogger("javax.enterprise.system").setLevel(Level.FINEST);
		//		Logger.getLogger("com.sun.enterprise.deployment.node").setLevel(Level.WARNING);

		// Set the log levels  to WARNING
		Logger.getLogger("").getHandlers()[0].setLevel(Level.WARNING);
		configSso(glassfish);

		glassfish.start();
		addUserToRealm(glassfish);

		deployWar(glassfish, APP1);
		deployWar(glassfish, APP2);
	}

	@AfterClass
	public static void shutdown() throws GlassFishException {
		System.out.println("Stopping the server");
		undeployApps(glassfish, APP1, APP2);
		glassfish.stop();
		glassfish.dispose();
		runtime.shutdown();
	}
}
