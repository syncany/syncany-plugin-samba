/*
 * Syncany, www.syncany.org
 * Copyright (C) 2011-2014 Philipp C. Heckel <philipp.heckel@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.syncany.plugins.samba;

import java.util.Map;

import org.syncany.plugins.PluginOptionSpec;
import org.syncany.plugins.PluginOptionSpec.ValueType;
import org.syncany.plugins.PluginOptionSpecs;
import org.syncany.plugins.transfer.StorageException;
import org.syncany.plugins.transfer.TransferSettings;

import com.google.common.base.Objects;

/**
 * The Samba connection represents the settings required to connect to an
 * Samba-based storage backend. It can be used to initialize/create an
 * {@link SambaTransferManager} and is part of the {@link SambaPlugin}.
 *
 * @author Christian Roth <christian.roth@port17.de>
 */
public class SambaTransferSettings extends TransferSettings {
	private String hostname;
	private String username;
	private String password;
	private String share;
	private String path;

	public String getHostname() {
		return hostname;
	}

	public void setHostname(String hostname) {
		this.hostname = hostname;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getShare() {
		return share;
	}

	public void setShare(String share) {
		this.share = share;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	@Override
	public void init(Map<String, String> optionValues) throws StorageException {
		getOptionSpecs().validate(optionValues);
		this.hostname = optionValues.get("hostname");
		this.username = optionValues.get("username");
		this.password = optionValues.get("password");
		this.share = optionValues.get("share");
		this.path = optionValues.get("path");
	}

	@Override
	public PluginOptionSpecs getOptionSpecs() {
		return new PluginOptionSpecs(
			new PluginOptionSpec("hostname", "Hostname", ValueType.STRING, true, false, null),
			new PluginOptionSpec("username", "Username", ValueType.STRING, true, false, null),
			new PluginOptionSpec("password", "Password", ValueType.STRING, true, true, null),
			new PluginOptionSpec("share", "Share", ValueType.STRING, true, false, null),
			new PluginOptionSpec("path", "Path", ValueType.STRING, false, false, "/")
		);
	}

	@Override
	public String toString() {
		return Objects.toStringHelper(this.getClass())
			.add("hostname", hostname)
			.add("share", share)
			.add("path", path)
			.add("username", username)
			.add("password", password != null ? "<hidden>" : "none")
			.toString();
	}
}
