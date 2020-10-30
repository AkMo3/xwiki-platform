/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.security.authentication.internal;

import javax.inject.Named;
import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.observation.ObservationManager;
import org.xwiki.security.authentication.api.AuthenticationConfiguration;
import org.xwiki.security.authentication.api.AuthenticationFailureEvent;
import org.xwiki.security.authentication.api.AuthenticationFailureLimitReachedEvent;
import org.xwiki.security.authentication.api.AuthenticationFailureStrategy;
import org.xwiki.test.annotation.BeforeComponent;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.web.Utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests of {@link DefaultAuthenticationFailureManager}.
 *
 * @version $Id$
 * @since 11.6RC1
 */
@ComponentTest
public class DefaultAuthenticationFailureManagerTest
{
    @InjectMockComponents
    private DefaultAuthenticationFailureManager defaultAuthenticationFailureManager;

    @MockComponent
    @Named("strategy1")
    private AuthenticationFailureStrategy strategy1;

    @MockComponent
    @Named("strategy2")
    private AuthenticationFailureStrategy strategy2;

    @MockComponent
    private AuthenticationConfiguration configuration;

    @MockComponent
    private ObservationManager observationManager;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @MockComponent
    @Named("currentmixed")
    private DocumentReferenceResolver<String> currentMixedDocumentReferenceResolver;

    @MockComponent
    @Named("local")
    private EntityReferenceSerializer<String> localEntityReferenceSerializer;

    @Mock
    private XWikiContext context;

    @Mock
    private XWikiDocument userDocument;

    @Mock
    private XWiki xWiki;

    private String failingLogin = "foobar";

    private DocumentReference userFailingDocumentReference = new DocumentReference("xwiki", "XWiki", failingLogin);

    @BeforeComponent
    public void configure(MockitoComponentManager componentManager) throws Exception
    {
        Utils.setComponentManager(componentManager);
        componentManager.registerComponent(ComponentManager.class, "context", componentManager);
    }

    @BeforeEach
    public void setup() throws Exception
    {
        when(configuration.getFailureStrategies()).thenReturn(new String[] { "strategy1", "strategy2" });
        when(configuration.getMaxAuthorizedAttempts()).thenReturn(3);
        when(configuration.getTimeWindow()).thenReturn(5);
        when(configuration.isAuthenticationSecurityEnabled()).thenReturn(true);
        when(contextProvider.get()).thenReturn(context);
        when(context.getMainXWiki()).thenReturn("xwiki");
        when(context.getWiki()).thenReturn(xWiki);
        when(xWiki.getDocument(any(DocumentReference.class), eq(context))).thenReturn(userDocument);
    }

    /**
     * Ensure that a AuthenticationFailureEvent is triggered.
     */
    @Test
    public void authenticationFailureIsTriggered()
    {
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        verify(this.observationManager, times(1)).notify(new AuthenticationFailureEvent(), this.failingLogin);
    }

    /**
     * Ensure that the limit threshold is working properly and the rights events are triggered.
     */
    @Test
    void authenticationFailureLimitReached()
    {
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        assertTrue(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        assertTrue(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));

        verify(this.observationManager, times(4)).notify(new AuthenticationFailureEvent(), this.failingLogin);
        verify(this.observationManager, times(2)).notify(new AuthenticationFailureLimitReachedEvent(),
            this.failingLogin);
        verify(this.strategy1, times(2)).notify(failingLogin);
        verify(this.strategy2, times(2)).notify(failingLogin);
    }

    @Test
    void authenticationFailureEmptyLogin()
    {
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(""));
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(null));
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(""));
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(null));

        verify(this.observationManager, times(2)).notify(new AuthenticationFailureEvent(), "");
        verify(this.observationManager, times(2)).notify(new AuthenticationFailureEvent(), null);
        verify(this.observationManager, never()).notify(eq(new AuthenticationFailureLimitReachedEvent()), any());
        verify(this.strategy1, never()).notify(any());
        verify(this.strategy2, never()).notify(any());
    }

    /**
     * Ensure that the time window configuration is taken into account properly.
     */
    @Test
    public void repeatedAuthenticationFailureOutOfTimeWindow() throws InterruptedException
    {
        when(configuration.getTimeWindow()).thenReturn(1);
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        Thread.sleep(1500);
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        assertTrue(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));

        verify(this.observationManager, times(5)).notify(new AuthenticationFailureEvent(), this.failingLogin);
        verify(this.observationManager, times(1)).notify(new AuthenticationFailureLimitReachedEvent(),
            this.failingLogin);
        verify(this.strategy1, times(1)).notify(failingLogin);
        verify(this.strategy2, times(1)).notify(failingLogin);
    }

    /**
     * Ensure that the max attempt configuration is taken into account properly.
     */
    @Test
    public void repeatedAuthenticationFailureDifferentThreshold()
    {
        when(configuration.getMaxAuthorizedAttempts()).thenReturn(5);
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        assertTrue(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));

        verify(this.observationManager, times(5)).notify(new AuthenticationFailureEvent(), this.failingLogin);
        verify(this.observationManager, times(1)).notify(new AuthenticationFailureLimitReachedEvent(),
            this.failingLogin);
        verify(this.strategy1, times(1)).notify(failingLogin);
        verify(this.strategy2, times(1)).notify(failingLogin);
    }

    /**
     * Ensure that the failure record reset is working properly.
     */
    @Test
    public void resetAuthFailureRecord()
    {
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        this.defaultAuthenticationFailureManager.resetAuthenticationFailureCounter(this.failingLogin);
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        assertTrue(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));

        verify(this.observationManager, times(5)).notify(new AuthenticationFailureEvent(), this.failingLogin);
        verify(this.observationManager, times(1)).notify(new AuthenticationFailureLimitReachedEvent(),
            this.failingLogin);
        verify(this.strategy1, times(1)).notify(failingLogin);
        verify(this.strategy2, times(1)).notify(failingLogin);
    }

    /**
     * Ensure that the failure record reset is working properly.
     */
    @Test
    public void resetAuthFailureRecordWithDocumentReference()
    {
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        this.defaultAuthenticationFailureManager.resetAuthenticationFailureCounter(this.userFailingDocumentReference);
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        assertTrue(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));

        verify(this.observationManager, times(5)).notify(new AuthenticationFailureEvent(), this.failingLogin);
        verify(this.observationManager, times(1)).notify(new AuthenticationFailureLimitReachedEvent(),
            this.failingLogin);
        verify(this.strategy1, times(1)).notify(failingLogin);
        verify(this.strategy2, times(1)).notify(failingLogin);
    }

    /**
     * Ensure that the threshold mechanism works properly with different login.
     */
    @Test
    public void recordAuthFailureDifferentLogin()
    {
        String login1 = this.failingLogin.toLowerCase();
        String login2 = this.failingLogin.toUpperCase();
        String login3 = "barfoo";

        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(login1));
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(login2));
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(login3));

        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(login1));
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(login2));
        assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(login3));

        assertTrue(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(login1));
        assertTrue(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(login2));
        assertTrue(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(login3));

        verify(this.observationManager, times(3)).notify(new AuthenticationFailureEvent(), login1);
        verify(this.observationManager, times(1)).notify(new AuthenticationFailureLimitReachedEvent(),
            login1);
        verify(this.strategy1, times(1)).notify(login1);
        verify(this.strategy2, times(1)).notify(login1);

        verify(this.observationManager, times(3)).notify(new AuthenticationFailureEvent(), login2);
        verify(this.observationManager, times(1)).notify(new AuthenticationFailureLimitReachedEvent(),
            login2);
        verify(this.strategy1, times(1)).notify(login2);
        verify(this.strategy2, times(1)).notify(login2);

        verify(this.observationManager, times(3)).notify(new AuthenticationFailureEvent(), login3);
        verify(this.observationManager, times(1)).notify(new AuthenticationFailureLimitReachedEvent(),
            login3);
        verify(this.strategy1, times(1)).notify(login3);
        verify(this.strategy2, times(1)).notify(login3);
    }

    /**
     * Ensure that the authentication threshold auth is deactivated if max attempt is set to 0
     */
    @Test
    public void deactivateThresholdAuthWithMaxAttempt()
    {
        when(this.configuration.getMaxAuthorizedAttempts()).thenReturn(0);

        for (int i = 0; i < 100; i++) {
            assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        }
        verify(this.observationManager, times(100)).notify(new AuthenticationFailureEvent(), this.failingLogin);
        verify(this.observationManager, never()).notify(new AuthenticationFailureLimitReachedEvent(),
            this.failingLogin);
    }

    /**
     * Ensure that the authentication threshold auth is deactivated if time window is set to 0
     */
    @Test
    public void deactivateThresholdAuthWithTimeWindow()
    {
        when(this.configuration.getTimeWindow()).thenReturn(0);

        for (int i = 0; i < 100; i++) {
            assertFalse(this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin));
        }
        verify(this.observationManager, times(100)).notify(new AuthenticationFailureEvent(), this.failingLogin);
        verify(this.observationManager, never()).notify(new AuthenticationFailureLimitReachedEvent(),
            this.failingLogin);
    }

    /**
     * Validate that getForm is working properly.
     */
    @Test
    public void getForm()
    {
        String formStrategy1 = "formStrategy1";
        String formStrategy2 = "formStrategy2";
        when(this.strategy1.getForm(eq(this.failingLogin))).thenReturn(formStrategy1);
        when(this.strategy2.getForm(eq(this.failingLogin))).thenReturn(formStrategy2);

        assertEquals("", this.defaultAuthenticationFailureManager.getForm(this.failingLogin));

        this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin);
        this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin);
        this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin);
        assertEquals(String.format("%s\n%s\n", formStrategy1, formStrategy2),
            this.defaultAuthenticationFailureManager.getForm(this.failingLogin));
    }

    /**
     * Validate that getErrorMessages is working properly.
     */
    @Test
    public void getErrorMessages()
    {
        String errorMessage1 = "errorMessage1";
        String errorMessage2 = "errorMessage2";
        when(this.strategy1.getErrorMessage(eq(this.failingLogin))).thenReturn(errorMessage1);
        when(this.strategy2.getErrorMessage(eq(this.failingLogin))).thenReturn(errorMessage2);

        assertEquals("", this.defaultAuthenticationFailureManager.getErrorMessage(this.failingLogin));

        this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin);
        this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin);
        this.defaultAuthenticationFailureManager.recordAuthenticationFailure(this.failingLogin);
        assertEquals(String.format("%s\n%s\n", errorMessage1, errorMessage2),
            this.defaultAuthenticationFailureManager.getErrorMessage(this.failingLogin));
    }

    /**
     * Validate that getForm is working properly.
     */
    @Test
    public void validateForm()
    {
        String login1 = this.failingLogin;
        String login2 = "barfoo";

        assertTrue(this.defaultAuthenticationFailureManager.validateForm(login1, null));
        assertTrue(this.defaultAuthenticationFailureManager.validateForm(login2, null));

        this.defaultAuthenticationFailureManager.recordAuthenticationFailure(login1);
        this.defaultAuthenticationFailureManager.recordAuthenticationFailure(login1);
        this.defaultAuthenticationFailureManager.recordAuthenticationFailure(login1);

        this.defaultAuthenticationFailureManager.recordAuthenticationFailure(login2);
        this.defaultAuthenticationFailureManager.recordAuthenticationFailure(login2);
        this.defaultAuthenticationFailureManager.recordAuthenticationFailure(login2);

        when(this.strategy1.validateForm(login1, null)).thenReturn(true);
        when(this.strategy2.validateForm(login1, null)).thenReturn(true);
        assertTrue(this.defaultAuthenticationFailureManager.validateForm(login1, null));

        when(this.strategy1.validateForm(login2, null)).thenReturn(true);
        when(this.strategy2.validateForm(login2, null)).thenReturn(false);
        assertFalse(this.defaultAuthenticationFailureManager.validateForm(login2, null));
    }

    /**
     * Validate that getUser is working properly.
     */
    @Test
    public void getUserNotFound() throws XWikiException
    {
        when(context.getMainXWiki()).thenReturn("mainwiki");
        when(context.getWikiId()).thenReturn("currentwiki");
        XWiki xwiki = mock(XWiki.class);
        XWikiDocument xWikiDocument = mock(XWikiDocument.class);

        when(context.getWiki()).thenReturn(xwiki);
        when(xwiki.getDocument(any(DocumentReference.class), eq(context))).thenReturn(xWikiDocument);
        when(xWikiDocument.isNew()).thenReturn(true);
        DocumentReference userReference = this.defaultAuthenticationFailureManager.findUser("foo");
        assertNull(userReference);
        DocumentReference globalReference = new DocumentReference("mainwiki", "XWiki", "foo");
        DocumentReference localReference = new DocumentReference("currentwiki", "XWiki", "foo");
        verify(xwiki, times(1)).getDocument(eq(globalReference), eq(context));
        verify(xwiki, times(1)).getDocument(eq(localReference), eq(context));
    }

    /**
     * Validate that getUser is working properly.
     */
    @Test
    public void getUserGlobalFound() throws XWikiException
    {
        when(context.getMainXWiki()).thenReturn("mainwiki");
        DocumentReference globalReference = new DocumentReference("mainwiki", "XWiki", "foo");
        DocumentReference localReference = new DocumentReference("currentwiki", "XWiki", "foo");
        XWiki xwiki = mock(XWiki.class);
        XWikiDocument xWikiDocument = mock(XWikiDocument.class);

        when(context.getWiki()).thenReturn(xwiki);
        when(xwiki.getDocument(eq(globalReference), eq(context))).thenReturn(xWikiDocument);
        when(xWikiDocument.isNew()).thenReturn(false);
        DocumentReference userReference = this.defaultAuthenticationFailureManager.findUser("foo");
        assertEquals(globalReference, userReference);

        verify(xwiki, times(1)).getDocument(eq(globalReference), eq(context));
        verify(xwiki, never()).getDocument(eq(localReference), eq(context));
    }

    /**
     * Validate that getUser is working properly.
     */
    @Test
    public void getUserLocalFound() throws XWikiException
    {
        when(context.getMainXWiki()).thenReturn("mainwiki");
        when(context.getWikiId()).thenReturn("currentwiki");
        DocumentReference globalReference = new DocumentReference("mainwiki", "XWiki", "foo");
        DocumentReference localReference = new DocumentReference("currentwiki", "XWiki", "foo");
        XWiki xwiki = mock(XWiki.class);
        when(context.getWiki()).thenReturn(xwiki);
        XWikiDocument xWikiLocalDocument = mock(XWikiDocument.class);
        XWikiDocument xWikiGlobalDocument = mock(XWikiDocument.class);
        when(xwiki.getDocument(eq(globalReference), eq(context))).thenReturn(xWikiGlobalDocument);
        when(xwiki.getDocument(eq(localReference), eq(context))).thenReturn(xWikiLocalDocument);
        when(xWikiGlobalDocument.isNew()).thenReturn(true);
        when(xWikiLocalDocument.isNew()).thenReturn(false);
        DocumentReference userReference = this.defaultAuthenticationFailureManager.findUser("foo");
        assertEquals(localReference, userReference);

        verify(xwiki, times(1)).getDocument(eq(globalReference), eq(context));
        verify(xwiki, times(1)).getDocument(eq(localReference), eq(context));
    }

    @Test
    public void strategiesAreRebuildInCaseOfReset()
    {
        when(configuration.getFailureStrategies()).thenReturn(new String[] { "strategy1" });
        when(configuration.getMaxAuthorizedAttempts()).thenReturn(1);
        this.defaultAuthenticationFailureManager.recordAuthenticationFailure("foo");
        verify(configuration, times(3)).getFailureStrategies();
        verify(strategy1, times(1)).notify("foo");
        verify(strategy2, never()).notify(any());

        // we change the configuration strategy, but we don't reset the list
        when(configuration.getFailureStrategies()).thenReturn(new String[] { "strategy2" });
        this.defaultAuthenticationFailureManager.recordAuthenticationFailure("foo");

        // the list is already existing, we still call the old strategy
        verify(configuration, times(6)).getFailureStrategies();
        verify(strategy1, times(1)).notify("foo");
        verify(strategy2, times(1)).notify(any());
    }
}
