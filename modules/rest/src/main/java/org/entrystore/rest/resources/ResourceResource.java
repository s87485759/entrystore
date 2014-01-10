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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.io.IOUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrQuery.ORDER;
import org.entrystore.repository.ResourceType;
import org.entrystore.repository.Data;
import org.entrystore.repository.Entry;
import org.entrystore.repository.Group;
import org.entrystore.repository.LocationType;
import org.entrystore.repository.Metadata;
import org.entrystore.repository.QuotaException;
import org.entrystore.repository.RepositoryProperties;
import org.entrystore.repository.RepresentationType;
import org.entrystore.repository.User;
import org.entrystore.repository.impl.ListImpl;
import org.entrystore.repository.impl.RDFResource;
import org.entrystore.repository.impl.StringResource;
import org.entrystore.repository.impl.converters.ConverterUtil;
import org.entrystore.repository.security.AuthorizationException;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.repository.util.FileOperations;
import org.entrystore.rest.util.JSONErrorMessages;
import org.entrystore.rest.util.RDFJSON;
import org.entrystore.rest.util.Util;
import org.ieee.ltsc.lom.LOM.Technical.Location;
import org.ieee.ltsc.lom.LOMUtil;
import org.ieee.ltsc.lom.impl.LOMImpl;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openrdf.model.Graph;
import org.openrdf.model.impl.GraphImpl;
import org.restlet.Request;
import org.restlet.data.Disposition;
import org.restlet.data.MediaType;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import org.restlet.ext.fileupload.RestletFileUpload;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.FileRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.Put;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.syndication.feed.synd.SyndContent;
import com.sun.syndication.feed.synd.SyndContentImpl;
import com.sun.syndication.feed.synd.SyndEntry;
import com.sun.syndication.feed.synd.SyndEntryImpl;
import com.sun.syndication.feed.synd.SyndFeed;
import com.sun.syndication.feed.synd.SyndFeedImpl;
import com.sun.syndication.io.FeedException;
import com.sun.syndication.io.SyndFeedOutput;

/**
 * This class is the resource for entries. 
 * 
 * @author Eric Johansson
 * @author Hannes Ebner
 */
public class ResourceResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(ResourceResource.class);

	@Override
	public void doInit() {
		Util.handleIfUnmodifiedSince(entry, getRequest());
	}

	/**
	 * GET
	 * 
	 * From the REST API:
	 * 
	 * <pre>
	 * GET {baseURI}/{portfolio-id}/resource/{entry-id}
	 * </pre>
	 * 
	 * @return The Representation as JSON
	 */
	@Get
	public Representation represent() {
		try {
			if (entry == null) {
				log.info("Cannot find an entry with that ID"); 
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND); 
				return new JsonRepresentation(JSONErrorMessages.errorCantNotFindEntry); 
			}

			/*
			 * RSS feed
			 */
			if (parameters.containsKey("syndication")) {
				try {
					if (getRM().getSolrSupport() == null) {
						getResponse().setStatus(Status.SERVER_ERROR_NOT_IMPLEMENTED);
						return new JsonRepresentation("{\"error\":\"Feeds are not supported by this installation\"}");
					}
					StringRepresentation rep = getSyndicationSolr(entry, parameters.get("syndication"));
					if (rep == null) {
						getResponse().setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
						return new JsonRepresentation(JSONErrorMessages.errorNotAContext); 
					}
					return rep; 
				} catch (IllegalArgumentException e) {
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
					return new JsonRepresentation(JSONErrorMessages.syndicationFormat); 
				}
			}

			/*
			 * Resource: 
			 * BuiltinTypes: List, String and None.
			 * RepresentationType: InformationResource, NamesResource and Unknown. 
			 */
			Representation result = null;

			// the check for resource safety is necessary to avoid an implicit
			// getMetadata() in the case of a PUT on (not yet) existant metadata
			// - this is e.g. the case if conditional requests are issued 
			if (getRequest().getMethod().isSafe()) {
				result = getResource();
			} else {
				result = new EmptyRepresentation();
			}

			Date lastMod = entry.getModifiedDate();
			if (lastMod != null) {
				result.setModificationDate(lastMod);
			}
			return result;
		} catch(AuthorizationException e) {
			log.error("unauthorizedGET");
			return unauthorizedGET();
		}
	}

	@Put
	public void storeRepresentation(Representation r) {
		if (entry == null) {
			log.info("Cannot find an entry with that ID"); 
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			return;
		}

		try {
			modifyResource();
		} catch(AuthorizationException e) {
			unauthorizedPUT();
		}
	}

	@Post
	public void acceptRepresentation(Representation r) {
		if (entry == null) {
			log.info("Cannot find an entry with that ID"); 
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			return;
		}

		try {
			if (parameters.containsKey("method")) {
				if ("delete".equalsIgnoreCase(parameters.get("method"))) {
					removeRepresentations();
				} else if ("put".equalsIgnoreCase(parameters.get("method"))) {
					storeRepresentation(r);
				}
			} else if (entry.getResourceType().equals(ResourceType.List) &&
					parameters.containsKey("import") &&
					MediaType.APPLICATION_ZIP.equals(getRequestEntity().getMediaType())) {
				getResponse().setStatus(importFromZIP(getRequestEntity()));
			} else if (entry.getResourceType().equals(ResourceType.List) &&
					parameters.containsKey("moveEntry") &&
					parameters.containsKey("fromList")) {
				// POST 3/resource/45?moveEntry=2/entry/34&fromList=2/resource/67
				ListImpl dest = (ListImpl) this.entry.getResource();
				String movableEntryString = parameters.get("moveEntry");
				String movableEntrySourceString = parameters.get("fromList");
				boolean removeAll = parameters.get("removeAll") != null;

				String baseURI = getRM().getRepositoryURL().toString();
				if (!baseURI.endsWith("/")) {
					baseURI += "/";
				}

				// Entry URI of the Entry to be moved
				URI movableEntry = movableEntryString.startsWith("http://") ? URI.create(movableEntryString) : URI.create(baseURI + movableEntryString);
				// Resource URI of the source List
				
				URI movableEntrySource = null;
				if (movableEntrySourceString != null) {
					movableEntrySource = movableEntrySourceString.startsWith("http://") ? URI.create(movableEntrySourceString) : URI.create(baseURI + movableEntrySourceString);
				}

				Entry movedEntry = null;
				String error = null;
				try {
					movedEntry = dest.moveEntryHere(movableEntry, movableEntrySource, removeAll);
				} catch (QuotaException qe) {
					error = qe.getMessage();
					log.warn(qe.getMessage());
					getResponse().setStatus(Status.CLIENT_ERROR_REQUEST_ENTITY_TOO_LARGE);
				} catch (IOException ioe) {
					error = ioe.getMessage();
					log.error(ioe.getMessage());
					getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				}
				if (error != null) {
					JSONObject jsonError = new JSONObject();
					try {
						jsonError.put("error", error);
					} catch (JSONException e) {
						log.error(e.getMessage());
					}
					getResponse().setEntity(new JsonRepresentation(jsonError));
					return;
				}
				
				JSONObject result = new JSONObject();
				try {
					result.put("entryURI", movedEntry.getEntryURI());
				} catch (JSONException e) {
					log.error(e.getMessage());
				}
				getResponse().setEntity(new JsonRepresentation(result));
				getResponse().setStatus(Status.SUCCESS_OK);
			} 
		} catch(AuthorizationException e) {
			unauthorizedPOST();
		}
	}

	@Delete
	public void removeRepresentations() {
		if (entry == null) {
			log.info("Cannot find an entry with that ID"); 
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			return;
		}
		
		try {
			deleteResource();
		} catch(AuthorizationException e) {
			unauthorizedDELETE();
		}
	}

	/**
	 * Deletes the resource if the entry has any.
	 */
	private void deleteResource() {
		/*
		 * List
		 */
		if (entry.getResourceType() == ResourceType.List) {
			ListImpl l = (ListImpl) entry.getResource();
			if (parameters.containsKey("recursive")) {
				l.removeTree();
			} else {
				l.setChildren(new Vector<URI>());
			}
		}

		/*
		 * None
		 */
		if (entry.getResourceType() == ResourceType.None ) {
			if(entry.getRepresentationType() == RepresentationType.InformationResource) {
				Data data = (Data)entry.getResource(); 
				if (data.delete() == false) {
					log.error("Unknown kind"); 
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST); 
					getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.errorUnknownKind));
				}
			}
		}
		/*
		 * String
		 */
		if (ResourceType.String.equals(entry.getResourceType())) {
			StringResource strRes = (StringResource) entry.getResource(); // FIXME ?!
			GraphImpl g = new GraphImpl(); 
			entry.setGraph(g); 
		}

	}

	protected boolean isFile(Entry entry) {
		if (entry != null) {
			return LocationType.Local.equals(entry.getLocationType()) &&
				ResourceType.None.equals(entry.getResourceType()) &&
				RepresentationType.InformationResource.equals(entry.getRepresentationType());
		} else {
			return false;
		}
	}

	private Status importFromZIP(Representation rep) {
		File tmpFile = null;
		try {
			tmpFile = writeStreamToTmpFile(rep.getStream());
			if (tmpFile != null && tmpFile.exists()) {
				ZipFile zipFile = new ZipFile(tmpFile);
				Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
				while (zipEntries.hasMoreElements()) {
					ZipEntry entry = zipEntries.nextElement();
					String nameLC = entry.getName();
					if (!entry.isDirectory() && (nameLC.endsWith(".xml") || nameLC.endsWith(".rdf"))) {
						InputStream fileIS = zipFile.getInputStream(entry);
						if (fileIS == null) {
							log.error("Unable to get InputStream of ZipEntry: " + nameLC);
							continue;
						}
						String fileString = null;
						try {
							StringWriter writer = new StringWriter();
							IOUtils.copy(fileIS, writer);
							fileString = writer.toString();
							if (fileString == null) {
								log.error("[IMPORT] Problem with reading ZipEntry into String");
								continue;
							}
						} finally {
							if (fileIS != null) {
								fileIS.close();
							}
						}
						if (nameLC.endsWith(".xml")) {
							importLOMResource(fileString);
						} else if (nameLC.endsWith(".rdf")) {
							importRDFResource(fileString);
						}
					}
				}
			} else {
				return Status.SERVER_ERROR_INTERNAL;
			}
		} catch (IOException ioe) {
			log.error(ioe.getMessage());
			return Status.SERVER_ERROR_INTERNAL;
		} finally {
			if (tmpFile != null) {
				tmpFile.delete();
			}
		}
		
		return Status.SUCCESS_CREATED;
	}
	
	public File writeStreamToTmpFile(InputStream is) throws IOException {
		File tmpFile = File.createTempFile("scam_import_", ".zip");
		log.info("[IMPORT] Created temporary file: " + tmpFile);
		FileOutputStream fos = new FileOutputStream(tmpFile);
		FileOperations.copyFile(is, fos);
		return tmpFile;
	}
	
	private void importLOMResource(String lomString) {
		LOMImpl lom = ConverterUtil.readLOMfromReader(new StringReader(lomString));
		Location techLoc = LOMUtil.getTechnicalLocation(lom, 0);
		if (techLoc != null) {
			URI resourceURI = null;
			try {
				try {
					resourceURI = new URI(URLDecoder.decode(techLoc.string().trim(), "UTF-8"));
				} catch (UnsupportedEncodingException e) {
					resourceURI = new URI(techLoc.string().trim());
				}
			} catch (URISyntaxException e) {
				log.error(e.getMessage());
				return;
			}
			if (resourceURI != null) {
				Graph metadataGraph = ConverterUtil.convertLOMtoGraph(lom, resourceURI);
				Set<Entry> entries = context.getByResourceURI(resourceURI);
				if (entries.isEmpty()) {
					Entry newEntry = context.createLink(null, resourceURI, entry.getResourceURI());
					newEntry.getLocalMetadata().setGraph(metadataGraph);
					log.info("[IMPORT] Created new entry with URI: " + newEntry.getEntryURI());
				} else {
					for (Entry existingEntry : entries) {
						if (existingEntry.getReferringListsInSameContext().isEmpty()) {
							((ListImpl) entry).addChild(existingEntry.getEntryURI());
						}
						Metadata existingMetadata = existingEntry.getLocalMetadata();
						if (existingMetadata != null) {
							existingMetadata.setGraph(metadataGraph);
							log.info("[IMPORT] Updated metadata of existing entry: " + existingEntry.getEntryURI());
						}
					}
				}
			}
		} else {
			log.error("[IMPORT] No LOM Technical Location found, unable to construct Resource URI");
		}
	}
	
	private void importRDFResource(String rdfString) {
		// TODO
	}
	
	public Set<Entry> getListChildrenRecursively(Entry listEntry) {
		Set<Entry> result = new HashSet<Entry>();
		if (ResourceType.List.equals(listEntry.getResourceType()) && LocationType.Local.equals(listEntry.getLocationType())) {
			org.entrystore.repository.List l = (org.entrystore.repository.List) listEntry.getResource();
			List<URI> c = l.getChildren();
			for (URI uri : c) {
				Entry e = getRM().getContextManager().getEntry(uri);
				if (e != null) {
					if (ResourceType.List.equals(e.getResourceType())) {
						result.addAll(getListChildrenRecursively(e));
					} else {
						result.add(e);
					}
				}
			}
		} else {
			result.add(listEntry);
		}
		return result;
	}
	
	public StringRepresentation getSyndicationSolr(Entry entry, String type) {
		if (getRM().getSolrSupport() == null) {
			return null;
		}
		
		int FEED_SIZE = 250;
		if (parameters.containsKey("feedSize")) {
			try {
				FEED_SIZE = Integer.parseInt(parameters.get("feedSize"));
			} catch (NumberFormatException nfe) {
				log.warn("Feed size parameter was not a legal Integer: " + nfe.getMessage());
			}
		}
		
		SyndFeed feed = new SyndFeedImpl();
		feed.setFeedType(type);

		ResourceType bt = entry.getResourceType();
		if (!ResourceType.Context.equals(bt) && !ResourceType.List.equals(bt)) {
			return null;
		}

		String solrQueryValue;
		String alias;

		if (ResourceType.Context.equals(bt)) {
			alias = getCM().getContextAlias(entry.getResourceURI());
			solrQueryValue = "context:";
		} else {
			alias = EntryUtil.getTitle(entry, "en");
			solrQueryValue = "lists:";
		}
		
		feed.setTitle("Feed of \"" + alias + "\"");
		feed.setDescription("A syndication feed containing the 50 most recent items from \"" + alias + "\"");
		feed.setLink(entry.getResourceURI().toString());
		
		solrQueryValue += entry.getResourceURI().toString().replaceAll(":", "\\\\:");
		SolrQuery solrQuery = new SolrQuery(solrQueryValue);
		solrQuery.setStart(0);
		solrQuery.setRows(1000);
		solrQuery.setSortField("modified", ORDER.desc);

		List<SyndEntry> syndEntries = new ArrayList<SyndEntry>();
		Set<Entry> searchEntries = getRM().getSolrSupport().sendQuery(solrQuery).getEntries();
		List<Entry> recursiveEntries = new LinkedList<Entry>();
		for (Entry e : searchEntries) {
			recursiveEntries.addAll(getListChildrenRecursively(e));
		}
		EntryUtil.sortAfterModificationDate(recursiveEntries, false, null);
		
		int limitedCount = 0;
		for (Entry e : recursiveEntries) {
			SyndEntry syndEntry;
			syndEntry = new SyndEntryImpl();
			syndEntry.setTitle(EntryUtil.getTitle(e, "en"));
			syndEntry.setPublishedDate(e.getCreationDate());
			syndEntry.setUpdatedDate(e.getModifiedDate());
			syndEntry.setLink(e.getResourceURI().toString());

			SyndContent description = new SyndContentImpl();
			description.setType("text/plain");
			
			Map<String, String> descriptions = EntryUtil.getDescriptions(e);
			Set<java.util.Map.Entry<String,String>> descEntrySet = descriptions.entrySet();
			String desc = null;
			for (Map.Entry<String, String> descEntry : descEntrySet) {
				desc = descEntry.getKey();
				if ("en".equals(descEntry.getValue())) {
					break;
				}
			}
			
			if (desc != null) {  
				description.setValue(desc);
			}

			syndEntry.setDescription(description);
			
			URI creator = e.getCreator();
			if (creator != null) {
				Entry creatorEntry = getRM().getPrincipalManager().getByEntryURI(creator);
				String creatorName = EntryUtil.getName(creatorEntry);
				if (creatorName != null) {
					syndEntry.setAuthor(creatorName);
				}
			}
			
			syndEntries.add(syndEntry);
			
			if (limitedCount++ >= FEED_SIZE) {
				break;
			}
		}

		feed.setEntries(syndEntries);
		String s = null;
		try {
			s = new SyndFeedOutput().outputString(feed, true);
		} catch (FeedException fe) {
			log.error(fe.getMessage());
			s = fe.getMessage();
		}
		
		String feedType = feed.getFeedType();
		MediaType mediaType = null;
		if (feedType != null) {
			if (feedType.startsWith("rss_")) {
				mediaType = MediaType.APPLICATION_RSS;
			} else if (feedType.startsWith("atom_")) {
				mediaType = MediaType.APPLICATION_ATOM;
			}
		}

		if (mediaType != null) {
			return new StringRepresentation(s, mediaType);
		} else {
			return new StringRepresentation(s);
		}
	}

	/**
	 * Gets the resource's JSON representation
	 * 
	 * @return JSON representation
	 */
	private Representation getResource() throws AuthorizationException {
		if (LocationType.Link.equals(entry.getLocationType()) ||
				LocationType.LinkReference.equals(entry.getLocationType()) ||
				LocationType.Reference.equals(entry.getLocationType())) {
			if (ResourceType.None.equals(entry.getResourceType())) {
				getResponse().setLocationRef(new Reference(entry.getResourceURI().toString()));
				getResponse().setStatus(Status.REDIRECTION_SEE_OTHER);
				return null;
			}
		} else if (LocationType.Local.equals(entry.getLocationType())) {

			JSONArray array = new JSONArray(); 

			/*** List ***/
			if (entry.getResourceType() == ResourceType.List) {
				org.entrystore.repository.List l = (org.entrystore.repository.List) entry.getResource(); 
				List<URI> uris = l.getChildren();
				Set<String> IDs = new HashSet<String>();
				for (URI u: uris) {
					String id = (u.toASCIIString()).substring((u.toASCIIString()).lastIndexOf('/')+1);
					IDs.add(id);
				}
				
				if (parameters.containsKey("sort") && (IDs.size() < 501)) {
					List<Entry> childrenEntries = new ArrayList<Entry>();
					for (String id : IDs) {
						Entry childEntry = this.context.get(id);
						if (childEntry != null) {
							childrenEntries.add(childEntry);
						} else {
							log.warn("Child entry " + id + " in context " + context.getURI() + " does not exist, but is referenced by a list.");
						}
					}
					
					Date before = new Date();
					boolean asc = true;
					if ("desc".equalsIgnoreCase(parameters.get("order"))) {
						asc = false;
					}
					ResourceType prioritizedResourceType = null;
					if (parameters.containsKey("prio")) {
						prioritizedResourceType = ResourceType.valueOf(parameters.get("prio"));
					}
					String sortType = parameters.get("sort");
					if ("title".equalsIgnoreCase(sortType)) {
						String lang = parameters.get("lang");
						EntryUtil.sortAfterTitle(childrenEntries, lang, asc, prioritizedResourceType);
					} else if ("modified".equalsIgnoreCase(sortType)) {
						EntryUtil.sortAfterModificationDate(childrenEntries, asc, prioritizedResourceType);
					} else if ("created".equalsIgnoreCase(sortType)) {
						EntryUtil.sortAfterCreationDate(childrenEntries, asc, prioritizedResourceType);
					} else if ("size".equalsIgnoreCase(sortType)) {
						EntryUtil.sortAfterFileSize(childrenEntries, asc, prioritizedResourceType);
					}
					long sortDuration = new Date().getTime() - before.getTime();
					log.debug("List entry sorting took " + sortDuration + " ms");
					
					for (Entry childEntry : childrenEntries) {
						URI childURI = childEntry.getEntryURI();
						String id = (childURI.toASCIIString()).substring((childURI.toASCIIString()).lastIndexOf('/')+1);
						array.put(id);
					}
				} else {
					if (IDs.size() > 500) {
						log.warn("No sorting performed because of list size bigger than 500 children");
					}
					for (String id : IDs) {
						array.put(id);
					}
				}
				
				return new JsonRepresentation(array.toString());
			}

			/*** String ***/
			if(entry.getResourceType() == ResourceType.String) {
				StringResource stringResource = (StringResource)entry.getResource(); 
				Graph graph = stringResource.getGraph(); 
				if (graph == null) {
					return new JsonRepresentation("{\"error\":\"The string value has not been set yet.\"}"); 
				}
				return new JsonRepresentation(RDFJSON.graphToRdfJson(graph));  
			}

			/*** Graph ***/
			if (ResourceType.Graph.equals(entry.getResourceType())) {
				RDFResource graphResource = (RDFResource) entry.getResource(); 
				Graph graph = graphResource.getGraph();
				if (graph == null) {
					getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
					return new JsonRepresentation("{\"error\":\"The graph has not been set\"}"); 
				} 
				return new JsonRepresentation(RDFJSON.graphToRdfJson(graph));  
			}

			/*** Context ***/
			if(entry.getResourceType() == ResourceType.Context || entry.getResourceType() == ResourceType.SystemContext) {
				org.entrystore.repository.Context c = (org.entrystore.repository.Context) entry.getResource(); 
				Set<URI> uris = c.getEntries(); 
				for(URI u: uris) {
					String entryId = (u.toASCIIString()).substring((u.toASCIIString()).lastIndexOf('/')+1);
					array.put(entryId); 					
				}
				return new JsonRepresentation(array.toString());	
			}

			/*** None ***/
			if(entry.getResourceType() == ResourceType.None) {

				// Local data
				if(entry.getRepresentationType() == RepresentationType.InformationResource) {
					File file = ((Data)entry.getResource()).getDataFile(); 
					if  (file != null) {
						String medTyp = entry.getMimetype();
						FileRepresentation rep = null;
						if (medTyp != null) {
							rep = new FileRepresentation(file, MediaType.valueOf(medTyp));
						} else {
							rep = new FileRepresentation(file, MediaType.ALL);
						}
						String fileName = entry.getFilename();
						if (fileName == null) {
							fileName = entry.getId();
						}
						Disposition disp = rep.getDisposition();
						disp.setFilename(fileName);
						if (parameters.containsKey("download")) {
							disp.setType(Disposition.TYPE_ATTACHMENT);
						} else {
							disp.setType(Disposition.TYPE_INLINE);
						}
						return rep;
					}
				}

				// DOES NOT HAVE ANY RESOURCE
				if(entry.getRepresentationType() == RepresentationType.NamedResource) {
				}

				// NOT USED YET
				if(entry.getRepresentationType() == RepresentationType.Unknown) {	
				}

			}

			/*** User ***/
			if(entry.getResourceType() == ResourceType.User) {
				JSONObject jsonUserObj = new JSONObject();  
				User user = (User) entry.getResource(); 
				try {
					jsonUserObj.put("name", user.getName());

					//jsonUserObj.put("password", user.getSecret());

					org.entrystore.repository.Context homeContext = user.getHomeContext();
					if (homeContext != null) {
						jsonUserObj.put("homecontext", homeContext.getEntry().getId());
					}

					String prefLang = user.getLanguage();
					if (prefLang != null) {
						jsonUserObj.put("language", prefLang);
					}

					return new JsonRepresentation(jsonUserObj);
				} catch (JSONException e) {
					log.error(e.getMessage());
				} 
			}

			/*** Group ***/
			if(entry.getResourceType() == ResourceType.Group) {
				JSONObject jsonGroupObj = new JSONObject(); 
				Group group = (Group) entry.getResource(); 
				JSONArray userArray = new JSONArray(); 
				try {
					for(User u : group.members()) {
						JSONObject childJSON = new JSONObject();
						JSONObject childInfo = new JSONObject(RDFJSON.graphToRdfJson(u.getEntry().getGraph())); 

						if(childInfo != null) {
							childJSON.accumulate("info_stub", childInfo);   
						} else {	
							childJSON.accumulate("info_stub", new JSONObject());  
						}

						JSONObject childMd = new JSONObject(RDFJSON.graphToRdfJson(u.getEntry().getLocalMetadata().getGraph())); 

						if(childMd != null) {
							childJSON.accumulate(RepositoryProperties.MD_PATH_STUB, childMd);   
						} else {	
							childJSON.accumulate(RepositoryProperties.MD_PATH_STUB, new JSONObject());  
						}
						userArray.put(childJSON); 
					}
					jsonGroupObj.put("children", userArray); 
					return new JsonRepresentation(jsonGroupObj);
				} catch (JSONException e) {
					log.error(e.getMessage());
				} 
			}


			log.error("Can not find the resource.");
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			return new JsonRepresentation(JSONErrorMessages.errorCantFindResource + " Builtin type: " + entry.getResourceType());
		}

		log.info("No resource available.");
		getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
		return new JsonRepresentation("{\"error\":\"No resource available for "+entry.getLocationType()+" entries \"}");

	}


	/**
	 * Set a resource to an entry.
	 */
	private void modifyResource() throws AuthorizationException {
		/*
		 * List and Group
		 */
		if (entry.getResourceType() == ResourceType.List || entry.getResourceType() == ResourceType.Group) {
			JSONObject entityJSON = null; 
			try {
				entityJSON = new JSONObject(getRequest().getEntity().getText());
				JSONArray childrenJSONArray = (JSONArray) entityJSON.get("resource");

				ArrayList<URI> newResource = new ArrayList<URI>(); 

				// Add new entries to the list. 
				for(int i = 0; i < childrenJSONArray.length(); i++) {
					String childId = childrenJSONArray.get(i).toString();
					Entry childEntry = context.get(childId);
					if(childEntry == null) {
						getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST); 
						log.error("Cannot update list, since one of the children does not exist.");
						return;
					} else {
						newResource.add(childEntry.getEntryURI());
					}
				}
				
				if (entry.getResourceType() == ResourceType.List) {
					org.entrystore.repository.List resourceList = (org.entrystore.repository.List) entry.getResource();
					resourceList.setChildren(newResource);
				} else {
					org.entrystore.repository.Group resourceGroup = (org.entrystore.repository.Group) entry.getResource();
					resourceGroup.setChildren(newResource); 
				}
				getResponse().setStatus(Status.SUCCESS_OK);
			} catch (JSONException e) {
				log.error("Wrong JSON syntax: " + e.getMessage()); 
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST); 
				getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.errorJSONSyntax));
			} catch (IOException e) {
				log.error("IOException: " + e.getMessage()); 
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST); 
				getResponse().setEntity(new JsonRepresentation("{\"error\":\"IOException\"}"));
			} catch (org.entrystore.repository.RepositoryException re) {
				log.warn(re.getMessage());
				getResponse().setStatus(Status.CLIENT_ERROR_CONFLICT);
				getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.errorChildExistsInList));
			}
			return; // success!
		}

		/*
		 * Data
		 */
		if (entry.getResourceType() == ResourceType.None){
			boolean textarea = this.parameters.keySet().contains("textarea");
			String error = null;

			if (MediaType.MULTIPART_FORM_DATA.equals(getRequest().getEntity().getMediaType(), true)) {
				try {
					RestletFileUpload upload = new RestletFileUpload(new DiskFileItemFactory());
					List<FileItem> items = upload.parseRequest(getRequest());
					Iterator<FileItem> iter = items.iterator();
					while (iter.hasNext()) {
						FileItem item = iter.next();
						((Data) entry.getResource()).setData(item.getInputStream());
						entry.setFileSize(((Data) entry.getResource()).getDataFile().length());
						String mimeType = item.getContentType();
						if (parameters.containsKey("mimeType")) {
							mimeType = parameters.get("mimeType");
						}
						entry.setMimetype(mimeType);
						String name = item.getName();
						if (name != null && name.length() != 0) {
							entry.setFilename(name.trim());
						}
					}
				} catch (FileUploadException e) {
					error = e.getMessage();
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST); 
				} catch (IOException ioe) {
					error = ioe.getMessage();
					getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				} catch (QuotaException qe) {
					error = qe.getMessage();
					getResponse().setStatus(Status.CLIENT_ERROR_REQUEST_ENTITY_TOO_LARGE);
				}
				

			} else {
				Request req = getRequest();
				try {
					((Data) entry.getResource()).setData(req.getEntity().getStream());
					entry.setFileSize(((Data) entry.getResource()).getDataFile().length());
					String mimeType = req.getEntity().getMediaType().toString();
					if (parameters.containsKey("mimeType")) {
						mimeType = parameters.get("mimeType");
					}
					entry.setMimetype(mimeType);
					Disposition disp = req.getEntity().getDisposition();
					if (disp != null) {
						String name = disp.getFilename();
						if (name != null && name.length() != 0) {
							entry.setFilename(name.trim());
						}
					}
				} catch (QuotaException qe) {
					error = qe.getMessage();
					getResponse().setStatus(Status.CLIENT_ERROR_REQUEST_ENTITY_TOO_LARGE);
				} catch (IOException ioe) {
					error = ioe.getMessage();
					getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				}
			}
			
			if (error != null) {
				if (textarea) {
					getResponse().setEntity("<textarea>" + error + "</textarea>", MediaType.TEXT_HTML);
				} else {
					JSONObject jsonError = new JSONObject();
					try {
						jsonError.put("error", error);
					} catch (JSONException jsone) {
						log.error(jsone.getMessage());
					}
					getResponse().setEntity(new JsonRepresentation(error));
				}
				return;
			}

			if (textarea) {
				getResponse().setEntity("<textarea>{\"success\":\"The file is uploaded\", \"format\": \""+entry.getMimetype()+"\"}</textarea>",MediaType.TEXT_HTML);
			} else {
				getResponse().setEntity(new JsonRepresentation("{\"success\":\"The file is uploaded\", \"format\": \""+entry.getMimetype()+"\"}"));				
			}
			getResponse().setStatus(Status.SUCCESS_CREATED);
		}

		/*** String  ***/
		// {"@id":"http://localhost:8080/scam/1/resource/11","sc:body":{"@language":"english","@value":"<h1>Title<\/h1>"}}
		if(entry.getResourceType() == ResourceType.String) {
			JSONObject entityJSON = null; 
			try {
				entityJSON = new JSONObject(getRequest().getEntity().getText());
				if(entityJSON.has("sc:body")) {
					JSONObject contentObj = (JSONObject)entityJSON.get("sc:body"); 
					StringResource stringResource = (StringResource)entry.getResource(); 

					if(contentObj.has("@value") && contentObj.has("@language")) {
						stringResource.setString(contentObj.getString("@value"), contentObj.getString("@language")); 
					} else if (contentObj.has("@value")) {
						stringResource.setString(contentObj.getString("@value"), null); 
					} 
				}
			} catch (JSONException e) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				getResponse().setEntity(new JsonRepresentation("{\"error\":\"Problem with input.\"}"));
			} catch (IOException e) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				getResponse().setEntity(new JsonRepresentation("{\"error\":\"Problem with input.\"}"));
			}
		}
		
		/*** Graph ***/
		if (ResourceType.Graph.equals(entry.getResourceType())) {
			RDFResource graphResource = (RDFResource) entry.getResource(); 
			if (graphResource != null) {
				Graph graph = null;
				try {
					graph = RDFJSON.rdfJsonToGraph(getRequest().getEntity().getText());
				} catch (IOException ioe) {
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
					getResponse().setEntity(new JsonRepresentation("{\"error\":\"Unable to read request entity\"}"));
					log.error("Unable to read request entity");
				}
				if (graph != null) {
					graphResource.setGraph(graph);
				} else {
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
					getResponse().setEntity(new JsonRepresentation("{\"error\":\"Unable to convert RDF/JSON to Graph\"}"));
					log.error("Unable to convert RDF/JSON to Sesame Graph");
				}
			} else {
				getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				getResponse().setEntity(new JsonRepresentation("{\"error\":\"No RDF resource found for this entry\"}"));
				log.error("No RDF resource found for this entry with ResourceType Graph");
			}
		}

		/*** User ***/
		if (entry.getResourceType() == ResourceType.User) {
			JSONObject entityJSON = null; 
			try {
				entityJSON = new JSONObject(getRequest().getEntity().getText());

				User resourceUser = (User) entry.getResource();
				if (entityJSON.has("name")) {
					String name =  entityJSON.getString("name");
					if (!resourceUser.setName(name)) {
						getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
						getResponse().setEntity(new JsonRepresentation("{\"error\":\"Name already taken.\"}"));
						return;
					}
				}
				if (entityJSON.has("password")) {
					String passwd =  entityJSON.getString("password");
					if (!resourceUser.setSecret(passwd)) {
						getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
						getResponse().setEntity(new JsonRepresentation("{\"error\":\"Password needs to be at least 8 characters long.\"}"));
						return;
					}
				}
				if (entityJSON.has("language")) {
					String prefLang = entityJSON.getString("language");
					if (prefLang.equals("")) {
						resourceUser.setLanguage(null);						
					} else if (!resourceUser.setLanguage(prefLang)) {
						getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
						getResponse().setEntity(new JsonRepresentation("{\"error\":\"Preferred language could not be set.\"}"));
						return;
					}
				}
				if (entityJSON.has("homecontext")) {
					String homeContext = entityJSON.getString("homecontext");
					Entry entryHomeContext = getCM().get(homeContext);
					if (entryHomeContext != null) {
						if (!(entryHomeContext.getResource() instanceof org.entrystore.repository.Context)
								|| !resourceUser.setHomeContext((org.entrystore.repository.Context) entryHomeContext.getResource())) {
							getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
							getResponse().setEntity(new JsonRepresentation("{\"error\":\"Given homecontext is not a context.\"}"));
							return;						
						}
					}
				}
				getResponse().setStatus(Status.SUCCESS_OK);
			} catch (JSONException e) {
				log.error("Wrong JSON syntax: " + e.getMessage()); 
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST); 
				getResponse().setEntity(new JsonRepresentation(JSONErrorMessages.errorJSONSyntax));
			} catch (IOException e) {
				log.error("IOException: " + e.getMessage()); 
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST); 
				getResponse().setEntity(new JsonRepresentation("{\"error\":\"IOException\"}"));
			}
		}
	}

}