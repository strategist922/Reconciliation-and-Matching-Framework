/*
 * Reconciliation and Matching Framework
 * Copyright © 2014 Royal Botanic Gardens, Kew
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.kew.rmf.core;

import org.kew.rmf.core.configuration.Configuration;


/**
 * The DataHandler does all the work.
 * It loads the data and runs a process as defined in the provided configuration.
 *
 * @param <Config>
 */
public interface DataHandler<Config extends Configuration> {

	public void setConfig(Config config);
	public void setDataLoader(DataLoader dataLoader);
	public void loadData() throws Exception;
	public void run() throws Exception;

}
