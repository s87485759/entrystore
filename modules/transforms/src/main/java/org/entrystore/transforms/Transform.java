package org.entrystore.transforms;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;

import org.openrdf.model.Graph;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.Value;

public class Transform implements Comparable<Transform>{
	public int prio = 0;
	public ArrayList<String> args;
	
	public void extractArguments(Graph graph, Resource resource) {
		Iterator<Statement> prios = graph.match(null, Pipeline.transformPriority, null);
		if (prios.hasNext()) {
			Value obj = prios.next().getObject();
			if (obj instanceof Literal) {
				this.prio = ((Literal) obj).integerValue().intValue();
			}
		}
		
		Iterator<Statement> args = graph.match(null, Pipeline.transformArgument, null);
		while (args.hasNext()) {
			this.args.add(args.next().getObject().stringValue());
		}
	}
	
	public int compareTo(Transform step) {
		return this.prio - step.prio;
	}
	
	public Graph transform(InputStream data, String mimetype) {
		return null;
	}

	public Graph transform(Graph graph) {
		return graph;
	}
}