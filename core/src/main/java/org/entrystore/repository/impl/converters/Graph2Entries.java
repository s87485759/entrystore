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

package org.entrystore.repository.impl.converters;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.entrystore.repository.BuiltinType;
import org.entrystore.repository.Context;
import org.entrystore.repository.Entry;
import org.entrystore.repository.RepositoryProperties;
import org.entrystore.repository.RepresentationType;
import org.entrystore.repository.impl.RDFResource;
import org.entrystore.repository.util.URISplit;
import org.openrdf.model.BNode;
import org.openrdf.model.Graph;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Converts an RDF graph to a set of Entries
 * 
 * @author Matthias Palmér
 */
public class Graph2Entries {
	
	private static ValueFactory vf = ValueFactoryImpl.getInstance();
	private static URI mergeResourceId = vf.createURI("http://ns.entrystore.com/entry/mergeResourceId");
	private static URI referenceResourceId = vf.createURI("http://ns.entrystore.com/entry/referenceResourceId");

	private static Logger log = LoggerFactory.getLogger(Graph2Entries.class);
	private Context context;
	
	public Graph2Entries(Context context) {
		this.context = context;
	}
	
	/**
	 * Detects and adds a set of entries from the graph via the anonymous closure algorithm starting from resources
	 * indicated with either of the two following properties that both indicate which entryId to use:<ul>
	 * <li>http://ns.entrystore.com/entry/mergeResourceId or the</li>
	 * <li>http://ns.entrystore.com/entry/referenceResourceId</li>
	 * </ul>
	 * The mergeResourceId indicates that the corresponding entry should be merged or created if it does not exist.
	 * The referenceResourceId only indicates that the relevant resource should be referenced.
	 * 
	 * @param graph
	 * @return a collection of the merged entries (updated or created), the referenced entries are not included in the collection.
	 */
	public Collection<Entry> merge(Graph graph, String resourceId) {
		log.info("About to update/create entries in context "+this.context.getEntry().getId()+".");
		Collection<Entry> entries = new HashSet<Entry>();
		
		HashMap<String, Resource> newResources = new HashMap<String, Resource>();
		HashMap<String, Resource> oldResources = new HashMap<String, Resource>();
		HashMap<Resource, Resource> translate = new HashMap<Resource, Resource>();

		Iterator<Statement> stmts = graph.match(null, referenceResourceId, null);
		while (stmts.hasNext()) {
			Statement statement = (Statement) stmts.next();
			String entryId = statement.getObject().stringValue();
			Resource re = newResources.get(entryId);
			if (re == null) {
				java.net.URI uri = URISplit.fabricateURI(
						context.getEntry().getRepositoryManager().getRepositoryURL().toString(), 
						context.getEntry().getId(), RepositoryProperties.DATA_PATH, entryId);
				re = vf.createURI(uri.toString());
			}
			translate.put(statement.getSubject(), re);
		}

		
		if (resourceId != null) {
			java.net.URI uri = URISplit.fabricateURI(
					context.getEntry().getRepositoryManager().getRepositoryURL().toString(), 
					context.getEntry().getId(), RepositoryProperties.DATA_PATH, resourceId);
			Resource newRe = vf.createURI(uri.toString());

			stmts = graph.match(null, mergeResourceId, null);
			while (stmts.hasNext()) {
				Statement statement = (Statement) stmts.next();
				translate.put(statement.getSubject(), newRe);
			}

			Entry entry = this.context.get(resourceId); //Try to fetch existing entry.
			if (entry == null) {
				entry = this.context.createResource(resourceId, BuiltinType.Graph, RepresentationType.InformationResource, null);					
			}
			Graph resg = this.translate(graph, translate);
			((RDFResource) entry.getResource()).setGraph(resg);

			Graph subg = this.extract(resg, newRe, new HashSet<Resource>(), new HashMap<Resource, Resource>());
			entry.getLocalMetadata().setGraph(subg);
			entries.add(entry);
			return entries;
		}
		
		stmts = graph.match(null, mergeResourceId, null);
		while (stmts.hasNext()) {
			Statement statement = (Statement) stmts.next();
			String entryId = statement.getObject().stringValue();
			java.net.URI uri = URISplit.fabricateURI(
					context.getEntry().getRepositoryManager().getRepositoryURL().toString(), 
					context.getEntry().getId(), RepositoryProperties.DATA_PATH, entryId);
			Resource newRe = vf.createURI(uri.toString());
			newResources.put(entryId, newRe);
			oldResources.put(entryId, statement.getSubject());
			translate.put(statement.getSubject(), newRe);
		}

		log.info("Found "+oldResources.size()+" resources that will be updated/created.");

		int newResCounter = 0;
		int updResCounter = 0;
		Collection<Resource> ignore = newResources.values();
		for (Iterator<String> it = newResources.keySet().iterator(); it.hasNext();) {
			String entryId = (String) it.next();
			Graph subg = this.extract(graph, oldResources.get(entryId), ignore, translate);
			Entry entry = this.context.get(entryId); //Try to fetch existing entry.
			if (entry == null) {  //If none exist, create it.
				entry = this.context.createResource(entryId, BuiltinType.None, RepresentationType.NamedResource, null);
				newResCounter++;
			} else {
				updResCounter++;
			}
			entry.getLocalMetadata().setGraph(subg);
			entries.add(entry);
		}
		log.info("Updated "+updResCounter+" existing entries and created "+ newResCounter+" new entries.");
		log.info("Finished updating/creating entries in context "+this.context.getEntry().getId()+".");		
		return entries;
	}
	
	/**
	 * Extracts a smaller graph by starting from a given resource and collects all direct and indirect outgoing triples, 
	 * only stopping when non-blank nodes or resources in the ignore set are encountered. It also replaces resources
	 * found in the translate map.
	 * 
	 * @param from the graph to extract triples from
	 * @param subject the resource to start detecting triples from
	 * @param ignore a set of resources that when encountered no further triples (outgoing) from that resource should be included
	 * @param translate a map of resources, the keys should be replaces with the values when encountered.
	 * @return the extracted subgraph, may be empty, not null.
	 */
	private Graph extract(Graph from, Resource subject, Collection<Resource> ignore, Map<Resource, Resource> translate) {
		Graph to = new GraphImpl();
		HashSet<Resource> collected = new HashSet<Resource>(ignore);
		this._extract(from, to, subject, collected, translate);
		return to;
	}
	private void _extract(Graph from, Graph to, Resource subject, Set<Resource> collected, Map<Resource, Resource> translate) {
		Iterator<Statement> stmts = from.match(subject, null, null);
		while (stmts.hasNext()) {
			Statement statement = (Statement) stmts.next();
			Resource subj = statement.getSubject();
			URI pred = statement.getPredicate();
			Value obj = statement.getObject();
			//Recursive step.
			if (obj instanceof BNode && !collected.contains(obj)) {
				collected.add((BNode) obj);
				this._extract(from, to, (BNode) obj, collected, translate);
			}

			if (pred.equals(mergeResourceId) || pred.equals(referenceResourceId)) {
				continue;
			}

			if (translate.get(subj) != null) {
				subj = translate.get(subj);
			}
			if (translate.get(obj) != null) {
				obj = translate.get(obj);
			}
			to.add(subj, pred, obj);
		}
	}
	private Graph translate(Graph from, Map<Resource, Resource> translate) {
		Graph to = new GraphImpl();
		Iterator<Statement> stmts = from.iterator();
		while (stmts.hasNext()) {
			Statement statement = (Statement) stmts.next();
			Resource subj = statement.getSubject();
			URI pred = statement.getPredicate();
			Value obj = statement.getObject();
			if (pred.equals(mergeResourceId) || pred.equals(referenceResourceId)) {
				continue;
			}
			
			if (translate.get(subj) != null) {
				subj = translate.get(subj);
			}
			if (translate.get(obj) != null) {
				obj = translate.get(obj);
			}
			to.add(subj, pred, obj);
		}
		
		return to;		
	}
}