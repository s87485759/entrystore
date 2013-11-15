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

package org.entrystore.rest.resources;

import org.entrystore.repository.User;
import org.entrystore.repository.security.AuthorizationException;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This resource provides basic information about the currently logged in user.
 * 
 * @author Hannes Ebner
 */
public class UserResource extends BaseResource {

	private static Logger log = LoggerFactory.getLogger(UserResource.class);

	@Get
	public Representation represent() throws ResourceException {
		try {
			User currentUser = getPM().getUser(getPM().getAuthenticatedUserURI());
			boolean guest = currentUser.getURI().equals(getPM().getGuestUser().getURI()); 
			
			JSONObject result = new JSONObject();
			
			try {
				result.put("user", currentUser.getName());
				result.put("id", currentUser.getEntry().getId());

				if (!guest) {
					org.entrystore.repository.Context homeContext = currentUser.getHomeContext();
					if (homeContext != null) {
						result.put("homecontext", homeContext.getEntry().getId());
					}
					String userLang = currentUser.getLanguage();
					if (userLang != null) {
						result.put("language", userLang);
					}
				}
			} catch (JSONException e) {
				JSONObject error = new JSONObject();
				try {
					error.put("error", e.getMessage());
				} catch (JSONException ignored) {}
				return new JsonRepresentation(error);
			}
			
			return new JsonRepresentation(result);
		} catch(AuthorizationException e) {
			return unauthorizedGET();
		}
	}

}