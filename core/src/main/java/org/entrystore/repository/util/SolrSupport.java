package org.entrystore.repository.util;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.entrystore.repository.AuthorizationException;
import org.entrystore.repository.Context;
import org.entrystore.repository.ContextManager;
import org.entrystore.repository.Data;
import org.entrystore.repository.Entry;
import org.entrystore.repository.LocationType;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.RepresentationType;
import org.entrystore.repository.PrincipalManager.AccessProperty;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.impl.LocalMetadataWrapper;
import org.entrystore.repository.impl.converters.ConverterUtil;
import org.entrystore.repository.impl.converters.NS;
import org.openrdf.model.Graph;
import org.openrdf.model.Literal;
import org.openrdf.model.Statement;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.model.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * @author Hannes Ebner
 */
public class SolrSupport {

	private static Logger log = LoggerFactory.getLogger(SolrSupport.class);

	private static int BATCH_SIZE = 1000;

	private boolean reindexing = false;

	private boolean extractFulltext = false;

	private RepositoryManager rm;

	private SolrServer solrServer;

	private Thread documentSubmitter;

	private final ConcurrentLinkedQueue<SolrInputDocument> postQueue = new ConcurrentLinkedQueue<SolrInputDocument>();

	public class SolrInputDocumentSubmitter extends Thread {

		@Override
		public void run() {
			while (!interrupted()) {
				if (!postQueue.isEmpty()) {
					UpdateRequest req = new UpdateRequest();
					req.setAction(AbstractUpdateRequest.ACTION.COMMIT, false, false);

					for (int i = 0; i < BATCH_SIZE; i++) {
						SolrInputDocument doc = postQueue.poll();
						if (doc == null) {
							break;
						}
						req.add(doc);
					}

					try {
						log.info("Sending commit with " + req.getDocuments().size() + " entries to Solr, "
								+ postQueue.size() + " documents remaining in post queue");
						req.process(solrServer);
					} catch (SolrServerException sse) {
						log.error(sse.getMessage(), sse);
					} catch (IOException ioe) {
						log.error(ioe.getMessage(), ioe);
					}
				} else {
					try {
						Thread.sleep(5000);
					} catch (InterruptedException ie) {
						log.info("Solr document submitter got interrupted, shutting down submitter thread");
						return;
					}
				}
			}
		}

	}

	public SolrSupport(RepositoryManager rm, SolrServer solrServer) {
		this.rm = rm;
		this.solrServer = solrServer;
		this.extractFulltext = "on".equalsIgnoreCase(rm.getConfiguration().getString(
				Settings.SOLR_EXTRACT_FULLTEXT, "off"));
		documentSubmitter = new SolrInputDocumentSubmitter();
		documentSubmitter.start();
	}

	public void shutdown() {
		if (documentSubmitter != null) {
			documentSubmitter.interrupt();
		}
	}

	public void clearSolrIndex(SolrServer solrServer) {
		UpdateRequest req = new UpdateRequest();
		req.setAction(AbstractUpdateRequest.ACTION.COMMIT, false, false);
		req.deleteByQuery("*:*");
		try {
			req.process(solrServer);
		} catch (SolrServerException sse) {
			log.error(sse.getMessage(), sse);
		} catch (IOException ioe) {
			log.error(ioe.getMessage(), ioe);
		}
	}

	/**
	 * Reindexes the Solr index. Does not return before the process is
	 * completed. All subsequent calls to this method are ignored until other
	 * eventually running reindexing processes are completed, this means that
	 * this method is thread-safe.
	 * 
	 * @param rm
	 *            A RepositoryManager instance.
	 */
	public void reindexLiterals() {
		if (solrServer == null) {
			log.warn("Ignoring request as Solr is not used by this instance");
			return;
		}

		synchronized (solrServer) {
			if (reindexing) {
				log.warn("Solr is already being reindexed: ignoring additional reindexing request");
				return;
			} else {
				reindexing = true;
			}
		}

		try {
			clearSolrIndex(solrServer);

			PrincipalManager pm = rm.getPrincipalManager();
			URI currentUser = pm.getAuthenticatedUserURI();
			try {
				pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
				ContextManager cm = rm.getContextManager();
				Set<URI> contexts = cm.getEntries();

				for (URI contextURI : contexts) {
					String id = contextURI.toString().substring(contextURI.toString().lastIndexOf("/") + 1);
					Context context = cm.getContext(id);
					if (context != null) {
						Set<URI> entries = context.getEntries();
						for (URI entryURI : entries) {
							if (entryURI != null) {
								Entry entry = cm.getEntry(entryURI);
								if (entry == null) {
									continue;
								}
								log.info("Adding document to Solr post queue: " + entryURI);
								postQueue.add(constructSolrInputDocument(entry, extractFulltext));
							}
						}
					}
				}
			} finally {
				pm.setAuthenticatedUserURI(currentUser);
			}
		} finally {
			reindexing = false;
		}
	}

	public static SolrInputDocument constructSolrInputDocument(Entry entry, boolean extractFulltext) {
		Graph mdGraph = entry.getMetadataGraph();
		URI resourceURI = entry.getResourceURI();

		SolrInputDocument doc = new SolrInputDocument();

		// URI
		doc.setField("uri", entry.getEntryURI().toString());

		// resource URI
		doc.setField("resource", resourceURI.toString());

		// resource URI of the surrounding context
		doc.setField("context", entry.getContext().getEntry().getResourceURI().toString());

		// RDF type
		String rdfTypeE = EntryUtil.getResource(entry.getGraph(), resourceURI, RDF.TYPE);
		if (rdfTypeE != null) {
			doc.addField("rdfType", rdfTypeE);
		}
		String rdfTypeM = EntryUtil.getResource(mdGraph, resourceURI, RDF.TYPE);
		if (rdfTypeM != null) {
			doc.addField("rdfType", rdfTypeM);
		}

		// creation date
		Date creationDate = entry.getCreationDate();
		if (creationDate != null) {
			doc.setField("created", creationDate);
		}

		// modification date
		Date modificationDate = entry.getModifiedDate();
		if (modificationDate != null) {
			doc.setField("modified", modificationDate);
		}

		// types
		doc.setField("builtinType", entry.getBuiltinType().name());
		doc.setField("locationType", entry.getLocationType().name());
		doc.setField("representationType", entry.getRepresentationType().name());

		// creator
		URI creator = entry.getCreator();
		if (creator != null) {
			doc.setField("creator", creator.toString());
		}

		// contributors
		doc.addField("contributors", entry.getContributors());

		// lists
		doc.addField("lists", entry.getReferringListsInSameContext());

		// ACL: admin, metadata r/w, resource r/w

		doc.addField("admin", entry.getAllowedPrincipalsFor(AccessProperty.Administer));
		doc.addField("metadata.r", entry.getAllowedPrincipalsFor(AccessProperty.ReadMetadata));
		doc.addField("metadata.rw", entry.getAllowedPrincipalsFor(AccessProperty.WriteMetadata));
		doc.addField("resource.r", entry.getAllowedPrincipalsFor(AccessProperty.ReadResource));
		doc.addField("resource.rw", entry.getAllowedPrincipalsFor(AccessProperty.WriteResource));

		// titles
		Map<String, String> titles = EntryUtil.getTitles(entry);
		if (titles != null && titles.size() > 0) {
			Set<String> langs = new HashSet<String>();
			for (String title : titles.keySet()) {
				doc.addField("title", title, 10);
				// we also store title.{lang} as dynamic field to be able to
				// sort after titles in a specific language
				String lang = titles.get(title);
				// we only want one title per language, otherwise sorting will not work
				if (lang != null && !langs.contains(lang)) {
					doc.addField("title." + lang, title, 10);
					langs.add(lang);
				}
			}
		}
		String firstName = EntryUtil.getFirstName(entry);
		String lastName = EntryUtil.getLastName(entry);
		String name = "";
		if (firstName != null) {
			name += firstName;
		}
		if (lastName != null) {
			name += " " + lastName;
		}
		if (name.length() > 0) {
			doc.addField("title", name, 10);
		}

		// description
		Map<String, String> descriptions = EntryUtil.getDescriptions(entry);
		if (descriptions != null && descriptions.size() > 0) {
			for (String description : descriptions.keySet()) {
				doc.addField("description", description);
				String lang = descriptions.get(description);
				if (lang != null) {
					doc.addField("description." + lang, description);
				}
			}
		}

		// keywords
		Map<String, String> keywords = EntryUtil.getKeywords(entry);
		if (keywords != null && keywords.size() > 0) {
			for (String keyword : keywords.keySet()) {
				doc.addField("keyword", keyword, 20);
				String lang = descriptions.get(keyword);
				if (lang != null) {
					doc.addField("keyword." + lang, keyword, 20);
				}
			}
		}

		// language of the resource
		String dcLang = EntryUtil.getLabel(mdGraph, resourceURI, new URIImpl(NS.dc + "language"), null);
		if (dcLang != null) {
			doc.addField("lang", dcLang);
		}
		String dctLang = EntryUtil.getLabel(mdGraph, resourceURI, new URIImpl(NS.dcterms + "language"), null);
		if (dctLang != null) {
			doc.addField("lang", dctLang);
		}

		// tags (dc:subject)
		Iterator<Statement> tags = mdGraph.match(null, new URIImpl(NS.dc + "subject"), null);
		while (tags.hasNext()) {
			doc.addField("tag", tags.next().getObject().stringValue());
		}

		// publicly viewable metadata?
		boolean guestReadable = false;
		PrincipalManager pm = entry.getRepositoryManager().getPrincipalManager();
		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
		try {
			pm.checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadMetadata);
			guestReadable = true;
		} catch (AuthorizationException ae) {
		}
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		doc.setField("public", guestReadable);

		// validated resource?
		doc.setField("validated", ConverterUtil.isValidated(mdGraph, resourceURI));

		// all literal values
		Graph metadata = entry.getMetadataGraph();
		if (metadata != null) {
			for (Statement s : metadata) {
				if (s.getObject() instanceof Literal) {
					Literal l = (Literal) s.getObject();
					// we only index plain literals (human-readable text)
					if (l.getDatatype() == null) {
						doc.addField("literal", l.getLabel());
					}
				}
			}
		}

		// all predicates
		metadata = entry.getMetadataGraph();
		if (metadata != null) {
			for (Statement s : metadata) {
				doc.addField("predicate", s.getPredicate().stringValue());
			}
		}

		// Full text extraction using Apache Tika
		if (extractFulltext && LocationType.Local.equals(entry.getLocationType())
				&& RepresentationType.InformationResource.equals(entry.getRepresentationType())
				&& entry.getResource() instanceof Data) {
			Data d = (Data) entry.getResource();
			File f = d.getDataFile();
			if (f != null && f.exists()) {
				String textContent = extractFulltext(f);
				if (textContent != null) {
					doc.addField("fulltext", textContent);
				}
			}
		}

		return doc;
	}

	public void postEntry(Entry entry, SolrServer solrServer) {
		PrincipalManager pm = entry.getRepositoryManager().getPrincipalManager();
		URI currentUser = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		try {
			log.info("Adding document to Solr post queue: " + entry.getEntryURI());
			postQueue.add(constructSolrInputDocument(entry, extractFulltext));
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}

	public void removeEntry(Entry entry, SolrServer solrServer) {
		UpdateRequest req = new UpdateRequest();
		req.setAction(AbstractUpdateRequest.ACTION.COMMIT, false, false);
		String escapedURI = StringUtils.replace(entry.getEntryURI().toString(), ":", "\\:");
		req.deleteByQuery("uri:" + escapedURI);
		try {
			log.info("Removing document from Solr: " + entry.getEntryURI());
			UpdateResponse res = req.process(solrServer);
			if (res.getStatus() > 0) {
				log.error("Removal request was unsuccessful with status " + res.getStatus());
			}
		} catch (SolrServerException sse) {
			log.error(sse.getMessage(), sse);
		} catch (IOException ioe) {
			log.error(ioe.getMessage(), ioe);
		}
	}

	private long sendQueryForEntryURIs(SolrQuery query, Set<URI> result, SolrServer solrServer, int offset, int limit) {
		if (offset > -1) {
			query.setStart(offset);
		}
		if (limit > -1) {
			query.setRows(limit);
		}

		long hits = -1;

		Date before = new Date();
		QueryResponse r = null;
		try {
			r = solrServer.query(query);
			SolrDocumentList docs = r.getResults();
			hits = docs.getNumFound();
			for (SolrDocument solrDocument : docs) {
				if (solrDocument.containsKey("uri")) {
					String uri = (String) solrDocument.getFieldValue("uri");
					if (uri != null) {
						result.add(URI.create(uri));
					}
				}
			}
		} catch (SolrServerException e) {
			log.error(e.getMessage());
		}
		log.info("Solr query took " + (new Date().getTime() - before.getTime()) + " ms");

		return hits;
	}

	public QueryResult sendQuery(SolrQuery query) {
		Set<URI> entries = new LinkedHashSet<URI>();
		Set<Entry> result = new LinkedHashSet<Entry>();
		long hits = -1;
		int limit = query.getRows();
		int offset = query.getStart();
		query.setIncludeScore(true);
		int resultFillIteration = 0;
		do {
			if (resultFillIteration++ > 0) {
				if (resultFillIteration > 10) {
					log.warn("Breaking after 10 result fill interations to prevent too many loops");
					break;
				}
				offset += 10;
				log.warn("Increasing offset to fill the result limit");
			}
			hits = sendQueryForEntryURIs(query, entries, solrServer, offset, -1);
			Date before = new Date();
			for (URI uri : entries) {
				try {
					Entry entry = rm.getContextManager().getEntry(uri);
					if (entry != null) {
						PrincipalManager pm = entry.getRepositoryManager().getPrincipalManager();
						// If linkReference or reference to a entry in the same
						// repository
						// check that the referenced metadata is accessible.
						if ((entry.getLocationType() == LocationType.Reference || entry.getLocationType() == LocationType.LinkReference)
								&& entry.getCachedExternalMetadata() instanceof LocalMetadataWrapper) {
							Entry refEntry = entry.getRepositoryManager().getContextManager()
									.getEntry(entry.getExternalMetadataURI());
							pm.checkAuthenticatedUserAuthorized(refEntry, AccessProperty.ReadMetadata);
						} else {
							// Check that the local metadata is accessible.
							pm.checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadMetadata);
						}
						result.add(entry);
						if (result.size() == limit) {
							// we have enough results
							break;
						}
					}
				} catch (AuthorizationException ae) {
					hits--;
					continue;
				}
			}
			log.info("Entry fetching took " + (new Date().getTime() - before.getTime()) + " ms");
		} while ((limit > result.size()) && (hits > (offset + limit)));

		return new QueryResult(result, hits);
	}

	public static String extractFulltext(File f) {
		return null;

		// FIXME this method works but is deactivated. the needed apache tika
		// framework has many dependencies so it is not activated in the pom.xml
		// of scam-core for now. please activate it there before enabling the
		// following code again.

		/*
		 * InputStream stream = null; String textContent = null; String mimeType
		 * = null; try { TikaConfig tc = TikaConfig.getDefaultConfig();
		 * InputStream mimeIS = null; try { mimeIS = new FileInputStream(f);
		 * mimeType = tc.getMimeRepository().getMimeType(mimeIS).getName(); }
		 * finally { if (mimeIS != null) { mimeIS.close(); } }
		 * 
		 * if (mimeType != null) { stream = new BufferedInputStream(new
		 * FileInputStream(f)); Parser parser = tc.getParser(mimeType); if
		 * (parser != null) { ContentHandler handler = new BodyContentHandler();
		 * try { log.info("Parsing document with MIME type " + mimeType + ": " +
		 * f.toString()); parser.parse(stream, handler, new Metadata(), new
		 * ParseContext()); textContent = handler.toString(); } catch (Exception
		 * e) { log.error("Unable to parse document: " + e.getMessage()); } }
		 * else { log.warn("Unable to detect parser for MIME type " + mimeType);
		 * } } else { log.warn("Unable to detect the MIME type"); } } catch
		 * (IOException e) { log.error(e.getMessage()); } finally { try { if
		 * (stream != null) { stream.close(); } } catch (IOException e) {
		 * log.error(e.getMessage()); } } return textContent;
		 */
	}

}