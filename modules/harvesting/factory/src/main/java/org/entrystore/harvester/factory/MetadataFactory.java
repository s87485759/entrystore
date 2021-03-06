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

package org.entrystore.harvester.factory;

import org.entrystore.harvester.MetadataType;

/**
 * 
 * @author eric
 *
 */
public class MetadataFactory {
	
	/**
	 * 
	 * @param metadataType
	 * @return a enum if success or null otherwise.
	 */
	public static MetadataType getMetadataType(String metadataType) {
		
		if(metadataType.equals("OAI_DC")) {
			return MetadataType.OAI_DC; 
		} else if(metadataType.equals("OAI_LOM")) {
			return MetadataType.OAI_LOM; 
		}
		return null;
	}

}
