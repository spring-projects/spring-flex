/*
 * Copyright 2002-2009 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.flex.hibernate4.security3;

import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.flex.core.LoginCommandConfigProcessor;
import org.springframework.flex.hibernate4.core.AbstractMessageBrokerTests;
import org.springframework.flex.security3.SpringSecurityLoginCommand;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.GrantedAuthorityImpl;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;

import flex.messaging.FlexContext;
import flex.messaging.security.LoginManager;

public class SpringSecurityLoginCommandTests extends AbstractMessageBrokerTests {

	@Mock
	private AuthenticationManager mgr;

	@Mock
	private SessionAuthenticationStrategy sas;

	@Mock
	private RememberMeServices rms;

	private MockHttpServletRequest request;

	private MockHttpServletResponse response;

	private SpringSecurityLoginCommand cmd;

	@Override
	public void setUp() throws Exception {
		this.request = new MockHttpServletRequest();
		this.response = new MockHttpServletResponse();
		FlexContext.setThreadLocalHttpRequest(this.request);
		FlexContext.setThreadLocalHttpResponse(this.response);
		MockitoAnnotations.initMocks(this);

		List<LogoutHandler> logoutHandlers = new ArrayList<LogoutHandler>();
		logoutHandlers.add(new SecurityContextLogoutHandler());
		this.cmd = new SpringSecurityLoginCommand(mgr);
		this.cmd.setLogoutHandlers(logoutHandlers);
		this.cmd.setSessionAuthenticationStrategy(sas);
		this.cmd.setRememberMeServices(rms);
		this.cmd.afterPropertiesSet();
	}

	@Override
	protected void tearDown() throws Exception {
		FlexContext.clearThreadLocalObjects();
	}

	public void testDoAuthentication_Failure() throws Exception {

		String username = "foo";
		String password = "bar";

		when(mgr.authenticate(isA(Authentication.class))).thenThrow(new UsernameNotFoundException("Authentication failed"));

		try {
			this.cmd.doAuthentication(username, password);
			fail("An AuthenticationException was not thrown");
		} catch (AuthenticationException ex) {
			// expected
		}
		verify(rms).loginFail(request, response);
	}

	public void testDoAuthentication_ValidLogin() throws Exception {
		String username = "foo";
		String password = "bar";

		Authentication auth = new UsernamePasswordAuthenticationToken(username, password);
		when(mgr.authenticate(isA(Authentication.class))).thenReturn(auth);

		Principal principal = this.cmd.doAuthentication(username, password);

		assertNotNull("A non-null Principal was not returned", principal);
		assertEquals(username, principal.getName());
		verify(sas).onAuthentication(auth, this.request, this.response);
		verify(rms).loginSuccess(request, response, auth);
	}

	public void testDoAuthorization_MatchingAuthority() throws Exception {
		List<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>();
		authorities.add(new GrantedAuthorityImpl("ROLE_USER"));
		authorities.add(new GrantedAuthorityImpl("ROLE_ABUSER"));
		Principal principal = new UsernamePasswordAuthenticationToken("foo", "bar", authorities);

		List<String> roles = new ArrayList<String>();
		roles.add("ROLE_ADMIN");
		roles.add("ROLE_USER");
        assertTrue("Authorization should pass", this.cmd.doAuthorization(principal, roles));
    }

    public void testDoAuthorization_NoMatchingAuthority() throws Exception {
        List<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>();
        authorities.add(new GrantedAuthorityImpl("ROLE_USER"));
        authorities.add(new GrantedAuthorityImpl("ROLE_ABUSER"));
        Principal principal = new UsernamePasswordAuthenticationToken("foo", "bar", authorities);

        List<String> roles = new ArrayList<String>();
        roles.add("ROLE_ADMIN");
        assertFalse("Authorization should not pass", this.cmd.doAuthorization(principal, roles));
    }

    public void testLoginCommandRegisteredWithDefaultConfig() throws Exception {

        setDirty();

        addStartupProcessor(new LoginCommandConfigProcessor(this.cmd));

        LoginManager mgr = getMessageBroker().getLoginManager();
        assertTrue("LoginManager not started", mgr.isStarted());
        assertSame("SpringSecurityLoginCommand not set on the LoginManager", this.cmd, mgr.getLoginCommand());
        assertFalse("Should default to per session authentication", mgr.isPerClientAuthentication());

    }

    public void testLoginCommandRegisteredWithPerClientConfig() throws Exception {

        setDirty();

        this.cmd.setPerClientAuthentication(true);

        LoginCommandConfigProcessor processor = new LoginCommandConfigProcessor(this.cmd);
        processor.setPerClientAuthentication(true);
        addStartupProcessor(processor);

        LoginManager mgr = getMessageBroker().getLoginManager();
        assertTrue("LoginManager not started", mgr.isStarted());
        assertSame("SpringSecurityLoginCommand not set on the LoginManager", this.cmd, mgr.getLoginCommand());
        assertTrue("Should be set to per client authentication", mgr.isPerClientAuthentication());
    }

    public void testLogoutWithDefaults() throws Exception {
        String username = "foo";
        String password = "bar";

        Principal principal = this.cmd.doAuthentication(username, password);

        MockHttpSession originalSession = (MockHttpSession) this.request.getSession();
        SecurityContext original = SecurityContextHolder.getContext();

        this.cmd.logout(principal);

        assertTrue("SecurityContext was not cleared", original != SecurityContextHolder.getContext());
        assertNull(this.request.getSession(false));
        assertTrue(originalSession.isInvalid());
    }
    
    public void testLogoutWithPerClientAuthentication() throws Exception {
        
        List<LogoutHandler> logoutHandlers = new ArrayList<LogoutHandler>();
        SecurityContextLogoutHandler handler = new SecurityContextLogoutHandler();
        handler.setInvalidateHttpSession(false);
        logoutHandlers.add(handler);
        this.cmd.setPerClientAuthentication(true);
        this.cmd.setLogoutHandlers(logoutHandlers);
        
        String username = "foo";
        String password = "bar";

        Principal principal = this.cmd.doAuthentication(username, password);

        MockHttpSession originalSession = (MockHttpSession) this.request.getSession();
        SecurityContext original = SecurityContextHolder.getContext();

        this.cmd.logout(principal);

        assertTrue("SecurityContext was not cleared", original != SecurityContextHolder.getContext());
        assertNotNull(this.request.getSession(false));
        assertTrue(!originalSession.isInvalid());
    }
}
