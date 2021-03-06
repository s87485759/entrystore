/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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


import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.harvester.Harvester;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.backup.BackupScheduler;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.EntryStoreApplication;
import org.entrystore.rest.util.CORSUtil;
import org.entrystore.rest.util.JSONErrorMessages;
import org.entrystore.rest.util.Util;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Header;
import org.restlet.data.MediaType;
import org.restlet.data.ServerInfo;
import org.restlet.data.Status;
import org.restlet.engine.header.HeaderUtils;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Options;
import org.restlet.resource.ServerResource;
import org.restlet.util.Series;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *<p> Base resource class that supports common behaviours or attributes shared by
 * all resources.</p>
 * 
 * @author Eric Johansson
 * @author Hannes Ebner 
 */
public abstract class BaseResource extends ServerResource {
	
	protected HashMap<String,String> parameters;
	
	MediaType format;
	
	protected String contextId;
	
	protected String entryId;
	
	protected org.entrystore.Context context;
	
	protected Entry entry;
	
	private static Logger log = LoggerFactory.getLogger(BaseResource.class);

	private static ServerInfo serverInfo;
	
	@Override
	public void init(Context c, Request request, Response response) {
		parameters = Util.parseRequest(request.getResourceRef().getRemainingPart());
		super.init(c, request, response);

		// we set a custom Server header in the HTTP response
		setServerInfo(this.getServerInfo());
		
		contextId = (String) request.getAttributes().get("context-id");
		if (getCM() != null && contextId != null) {
			context = getCM().getContext(contextId);
			if (context == null) {
				log.info("There is no context " + contextId);
			}
		}
		
		entryId = (String) request.getAttributes().get("entry-id");
		if (context != null && entryId != null) {
			entry = context.get(entryId);
			if (entry == null) {
				log.info("There is no entry " + entryId + " in context " + contextId);
			}
		}
		
		if (parameters.containsKey("format")) {
			String format = parameters.get("format");
			if (format != null) {
				this.format = new MediaType(format);
			}
		}
	}

	@Override
	public ServerInfo getServerInfo() {
		if (serverInfo == null) {
			ServerInfo si = super.getServerInfo();
			si.setAgent("EntryStore/" + EntryStoreApplication.getVersion());
			serverInfo = si;
		}
		return serverInfo;
	}

	/**
	 * Sends a response with CORS headers according to the configuration.
	 */
	@Options
	public Representation preflightCORS() {
		if ("off".equalsIgnoreCase(getRM().getConfiguration().getString(Settings.CORS, "off"))) {
			log.info("Received CORS preflight request but CORS support is disabled");
			setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
			return null;
		}
		getResponse().setEntity(new EmptyRepresentation());
		Series reqHeaders = (Series) getRequest().getAttributes().get("org.restlet.http.headers");
		String origin = reqHeaders.getFirstValue("Origin", true);
		if (origin != null) {
			CORSUtil cors = CORSUtil.getInstance(getRM().getConfiguration());
			if (!cors.isValidOrigin(origin)) {
				log.info("Received CORS preflight request with disallowed origin");
				//setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
				return new EmptyRepresentation();
			}

			Series<Header> respHeaders = new Series<Header>(Header.class);
			respHeaders.set("Access-Control-Allow-Origin", origin);
			respHeaders.set("Access-Control-Allow-Methods", "HEAD, GET, PUT, POST, DELETE, OPTIONS");
			respHeaders.set("Access-Control-Allow-Credentials", "true");
			if (cors.getAllowedHeaders() != null) {
				respHeaders.set("Access-Control-Allow-Headers", cors.getAllowedHeaders());
			}
			if (cors.getMaxAge() > -1) {
				respHeaders.set("Access-Control-Max-Age", Integer.toString(cors.getMaxAge()));
			}
			HeaderUtils.copyExtensionHeaders(respHeaders, getResponse());
		}
		return getResponse().getEntity();
	}

	/**
	 * Gets the current {@link ContextManager}
	 * @return The current {@link ContextManager} for the contexts.
	 */
	public ContextManager getCM() {
		return ((EntryStoreApplication) getContext().getAttributes().get(EntryStoreApplication.KEY)).getCM();
	}

	/**
	 * Gets the current {@link ContextManager}
	 * @return The current {@link ContextManager} for the contexts.
	 */
	public PrincipalManager getPM() {
		Map<String, Object> map = getContext().getAttributes();
		return ((EntryStoreApplication) map.get(EntryStoreApplication.KEY)).getPM();
	}

	/**
	 * Gets the current {@link RepositoryManager}.
	 * @return the current {@link RepositoryManager}.
	 */
	public RepositoryManagerImpl getRM() {
		return ((EntryStoreApplication) getContext().getAttributes().get(EntryStoreApplication.KEY)).getRM();
	}

	public ArrayList<Harvester> getHarvesters() {
		return ((EntryStoreApplication) getContext().getAttributes().get(EntryStoreApplication.KEY)).getHarvesters();
	}
	
	public BackupScheduler getBackupScheduler() {
		return ((EntryStoreApplication) getContext().getAttributes().get(EntryStoreApplication.KEY)).getBackupScheduler();
	}

	public Representation unauthorizedHEAD() {
		log.info("Unauthorized HEAD");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		return new EmptyRepresentation();
	}

	public Representation unauthorizedGET() {
		log.info("Unauthorized GET");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		
		List<MediaType> supportedMediaTypes = new ArrayList<MediaType>();
		supportedMediaTypes.add(MediaType.APPLICATION_JSON);
		MediaType preferredMediaType = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
		if (MediaType.APPLICATION_JSON.equals(preferredMediaType)) {
			return new JsonRepresentation(JSONErrorMessages.unauthorizedGET);
		} else {
			return new EmptyRepresentation();
		}
	}

	public void unauthorizedDELETE() {
		log.info("Unauthorized DELETE");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		if (MediaType.APPLICATION_JSON.equals(getRequest().getEntity().getMediaType())) {
			getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.unauthorizedDELETE));
		}
	}

	public void unauthorizedPOST() {
		log.info("Unauthorized POST");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		if (MediaType.APPLICATION_JSON.equals(getRequest().getEntity().getMediaType())) {
			getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.unauthorizedPOST));
		}
	}

	public void unauthorizedPUT() {
		log.info("Unauthorized PUT");
		getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
		if (MediaType.APPLICATION_JSON.equals(getRequest().getEntity().getMediaType())) {
			getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.unauthorizedPUT));
		}
	}
	
}