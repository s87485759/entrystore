/**
 * Copyright (c) 2007-2010
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.entrystore.repository.security;

import java.util.Iterator;

import org.entrystore.repository.Entry;
import org.entrystore.repository.User;
import org.entrystore.repository.PrincipalManager.AccessProperty;
import org.openrdf.model.Statement;


public class AuthorizationException extends RuntimeException {
	
	private Entry entry;
	private AccessProperty accessProperty;
	private User user;

	public AuthorizationException(User user, Entry entry, AccessProperty ap) {
		super();
		this.user = user;
		this.accessProperty = ap;
		this.entry = entry;
	}

	public AccessProperty getAccessProperty() {
		return accessProperty;
	}

	public Entry getEntry() {
		return entry;
	}

	public User getUser() {
		return user;
	}
}