package com.walmartlabs.concord.server.boot;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2020 Walmart Inc.
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

import com.walmartlabs.concord.server.security.rememberme.ConcordRememberMeManager;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.pam.AtLeastOneSuccessfulStrategy;
import org.apache.shiro.authc.pam.ModularRealmAuthenticator;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.mgt.WebSecurityManager;
import org.apache.shiro.web.session.mgt.ServletContainerSessionManager;
import org.eclipse.sisu.Typed;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.Set;

@Named
@Typed({SecurityManager.class, WebSecurityManager.class})
@Singleton
public class ConcordSecurityManager extends DefaultWebSecurityManager {

    @Inject
    public ConcordSecurityManager(Set<Realm> realms, ConcordRememberMeManager rememberMeManager) {
        super(realms);
        setSessionManager(new ServletContainerSessionManager());
        setRememberMeManager(rememberMeManager);

        ((ModularRealmAuthenticator)getAuthenticator()).setAuthenticationStrategy(new AtLeastOneSuccessfulStrategy() {
            @Override
            public AuthenticationInfo afterAttempt(Realm realm, AuthenticationToken token, AuthenticationInfo singleRealmInfo, AuthenticationInfo aggregateInfo, Throwable t) throws AuthenticationException {
                if (t instanceof AuthenticationException) {
                    throw (AuthenticationException)t;
                }
                return super.afterAttempt(realm, token, singleRealmInfo, aggregateInfo, t);
            }
        });
    }
}
