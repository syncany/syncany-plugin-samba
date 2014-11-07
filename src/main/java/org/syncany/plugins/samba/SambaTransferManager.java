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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;
import jcifs.smb.SmbFileOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.syncany.config.Config;
import org.syncany.plugins.transfer.AbstractTransferManager;
import org.syncany.plugins.transfer.StorageException;
import org.syncany.plugins.transfer.StorageFileNotFoundException;
import org.syncany.plugins.transfer.StorageMoveException;
import org.syncany.plugins.transfer.TransferManager;
import org.syncany.plugins.transfer.files.ActionRemoteFile;
import org.syncany.plugins.transfer.files.DatabaseRemoteFile;
import org.syncany.plugins.transfer.files.MultichunkRemoteFile;
import org.syncany.plugins.transfer.files.RemoteFile;
import org.syncany.plugins.transfer.files.SyncanyRemoteFile;
import org.syncany.plugins.transfer.files.TempRemoteFile;
import org.syncany.plugins.transfer.files.TransactionRemoteFile;
import org.syncany.util.FileUtil;

/**
 * Implements a {@link TransferManager} based on an Samba storage backend for the
 * {@link SambaTransferPlugin}.
 * 
 * <p>Using an {@link SambaTransferSettings}, the transfer manager is configured and uses
 * a well defined Samba share and folder to store the Syncany repository data. While repo and
 * master file are stored in the given folder, databases and multichunks are stored
 * in special sub-folders:
 * 
 * <ul>
 * <li>The <tt>databases</tt> folder keeps all the {@link DatabaseRemoteFile}s</li>
 * <li>The <tt>multichunks</tt> folder keeps the actual data within the {@link MultiChunkRemoteFile}s</li>
 * </ul>
 * 
 * <p>All operations are auto-connected, i.e. a connection is automatically
 * established.
 *
 * @author Christian Roth <christian.roth@port17.de>
 */
public class SambaTransferManager extends AbstractTransferManager {
	private static final Logger logger = Logger.getLogger(SambaTransferManager.class.getSimpleName());

	private NtlmPasswordAuthentication authentication;
	private String repoPath;
	private String multichunksPath;
	private String databasesPath;
	private String actionsPath;
	private String transactionsPath;
	private String tempPath;

	public SambaTransferManager(SambaTransferSettings connection, Config config) {
		super(connection, config);

		this.repoPath = "smb://" + connection.getHostname() + "/" + connection.getShare();
		this.multichunksPath = "/multichunks/";
		this.databasesPath = "/databases/";
		this.actionsPath = "/actions/";
		this.transactionsPath = "/transactions/";
		this.tempPath = "/temporary/";
		
		this.authentication = new NtlmPasswordAuthentication("", connection.getUsername(), connection.getPassword());

		if (logger.isLoggable(Level.INFO)) {
			logger.log(Level.INFO, "Samba: RepoPath is " + repoPath);
		}
	}

	public SambaTransferSettings getSettings() {
		return (SambaTransferSettings) settings;
	}

	@Override
	public void connect() throws StorageException {
		// make a connect
		try {
			new SmbFile(repoPath, authentication).exists();
		}
		catch (Exception e) {
			throw new StorageException("Unable to connect to target at " + repoPath + "/" + getSettings().getPath(), e);
		}
	}

	@Override
	public void disconnect() {
		// Nothing
	}

	@Override
	public void init(boolean createIfRequired) throws StorageException {
		connect();
		
		try {
			if (!testTargetExists() && createIfRequired) {
				new SmbFile(repoPath + "/" + getSettings().getPath(), authentication).mkdirs();
			}

			createSmbFile(RemoteFile.createRemoteFile(multichunksPath, SambaRemoteFile.class)).mkdir();
			createSmbFile(RemoteFile.createRemoteFile(databasesPath, SambaRemoteFile.class)).mkdir();
			createSmbFile(RemoteFile.createRemoteFile(actionsPath, SambaRemoteFile.class)).mkdir();
			createSmbFile(RemoteFile.createRemoteFile(transactionsPath, SambaRemoteFile.class)).mkdir();
			createSmbFile(RemoteFile.createRemoteFile(tempPath, SambaRemoteFile.class)).mkdir();
		}
		catch (MalformedURLException | SmbException e) {
			throw new StorageException("init: Cannot create required directories", e);
		}
		finally {
			disconnect();
		}
	}

	@Override
	public void download(RemoteFile remoteFile, File localFile) throws StorageException {
		if (remoteFile.getName().equals(".") && !remoteFile.getName().equals("..")) {
			return;
		}

		try {
			// Download file
			File tempFile = createTempFile(localFile.getName());
			OutputStream tempFOS = new FileOutputStream(tempFile);
			SmbFile requestedSmbFile = createSmbFile(remoteFile);

			if (logger.isLoggable(Level.INFO)) {
				logger.log(Level.INFO, "Samba: Downloading {0} to temp file {1}", new Object[]{requestedSmbFile.getPath(), tempFile});
			}

			try {
				SmbFileInputStream smbfis = new SmbFileInputStream(requestedSmbFile);
				IOUtils.copy(smbfis, tempFOS);

				tempFOS.close();
				smbfis.close();
			}
			catch (IOException e) {
				logger.log(Level.WARNING, "Samba: Downloading FAILED. {0} to temp file {1}", new Object[] { requestedSmbFile.getPath(), tempFile });
				throw new StorageFileNotFoundException("Samba: Downloading FAILED: " + requestedSmbFile.getPath(), e);
			}

			// Move file
			if (logger.isLoggable(Level.INFO)) {
				logger.log(Level.INFO, "Samba: Renaming temp file {0} to file {1}", new Object[]{tempFile, localFile});
			}

			localFile.delete();
			FileUtils.moveFile(tempFile, localFile);
			tempFile.delete();
		}
		catch (IOException ex) {
			logger.log(Level.SEVERE, "Error while downloading file " + remoteFile.getName(), ex);
			throw new StorageException(ex);
		}
	}

	@Override
	public void upload(File localFile, RemoteFile remoteFile) throws StorageException {
		try {
			// Upload to temp file
			InputStream fileFIS = new FileInputStream(localFile);
			SmbFile tempSmbFile = createSmbFile(RemoteFile.createRemoteFile("/temp-" + remoteFile.getName(), SambaRemoteFile.class));

			if (logger.isLoggable(Level.INFO)) {
				logger.log(Level.INFO, "Samba: Uploading {0} to temp file {1}", new Object[]{localFile, tempSmbFile.getPath()});
			}

			SmbFileOutputStream smbfos = new SmbFileOutputStream(tempSmbFile);
			IOUtils.copy(fileFIS, smbfos);

			fileFIS.close();
			smbfos.close();

			// Move
			SmbFile smbFile = createSmbFile(remoteFile);
			if (logger.isLoggable(Level.INFO)) {
				logger.log(Level.INFO, "Samba: Renaming temp file {0} to {1}", new Object[]{tempSmbFile.getPath(), smbFile.getPath()});
			}

			tempSmbFile.renameTo(smbFile);
		}
		catch (IOException ex) {
			logger.log(Level.SEVERE, "Could not upload file " + localFile + " to " + remoteFile.getName(), ex);
			throw new StorageException(ex);
		}
	}

	@Override
	public boolean delete(RemoteFile remoteFile) throws StorageException {
		try {
			createSmbFile(remoteFile).delete();
			return true;
		}
		catch (IOException ex) {
			logger.log(Level.SEVERE, "Could not delete file " + remoteFile.getName(), ex);
			throw new StorageException(ex);
		}
	}
	
	@Override
	public void move(RemoteFile sourceFile, RemoteFile targetFile) throws StorageException {
		try {
			SmbFile sourceRemoteFile = createSmbFile(sourceFile);
			SmbFile targetRemoteFile = createSmbFile(targetFile);
			
			sourceRemoteFile.renameTo(targetRemoteFile);
		}
		catch (SmbException e) {
			logger.log(Level.SEVERE, "Could not rename/move file " + sourceFile + " to " + targetFile, e);
			throw new StorageMoveException("Could not rename/move file " + sourceFile + " to " + targetFile, e);
		}
		catch (Exception e) {
			logger.log(Level.SEVERE, "Invalid file name for source or target file: " + sourceFile + " to " + targetFile, e);
			throw new StorageException("Invalid file name for source or target file: " + sourceFile + " to " + targetFile, e);
		}
	}

	@Override
	public <T extends RemoteFile> Map<String, T> list(Class<T> remoteFileClass) throws StorageException {
		try {
			// List folder
			SmbFile remoteSmbFolder = createSmbFile(RemoteFile.createRemoteFile(getRemoteFilePath(remoteFileClass), SambaRemoteFile.class));

			// Create RemoteFile objects
			Map<String, T> remoteFiles = new HashMap<String, T>();

			for (SmbFile entry : remoteSmbFolder.listFiles()) {
				try {
					T remoteFile = RemoteFile.createRemoteFile(entry.getName(), remoteFileClass);
					remoteFiles.put(entry.getName(), remoteFile);
				}
				catch (Exception e) {
					logger.log(Level.INFO, "Cannot create instance of " + remoteFileClass.getSimpleName() + " for file " + entry.getName() + "; maybe invalid file name pattern. Ignoring file.");
				}
			}
			return remoteFiles;
		}
		catch (IOException e) {
			logger.log(Level.SEVERE, "Unable to list Samba directory.", e);
			throw new StorageException(e);
		}
	}

	@Override
	public boolean testTargetCanWrite() {
		try {
			if (createSmbFile(null).isDirectory()) {
				SmbFile smbfile = createSmbFile(RemoteFile.createRemoteFile("syncany-write-test", SambaRemoteFile.class));

				SmbFileOutputStream smbFIS = new SmbFileOutputStream(smbfile);
				smbFIS.write("test".getBytes());
				smbFIS.close();

				smbfile.delete();

				logger.log(Level.INFO, "testTargetCanWrite: Can write, test file created/deleted successfully.");
				return true;
			}
			else {
				logger.log(Level.INFO, "testTargetCanWrite: Can NOT write, target does not exist.");
				return false;
			}
		}
		catch (Exception e) {
			logger.log(Level.INFO, "testTargetCanWrite: Can NOT write to target.", e);
			return false;
		}
	}

	@Override
	public boolean testTargetExists() {
		try {
			if (createSmbFile(null).isDirectory()) {
				logger.log(Level.INFO, "testTargetExists: Target does exist.");
				return true;
			}
			else {
				logger.log(Level.INFO, "testTargetExists: Target does NOT exist.");
				return false;
			}
		}
		catch (Exception e) {
			logger.log(Level.WARNING, "testTargetExists: Target does NOT exist, error occurred.", e);
			return false;
		}
	}

	@Override
	public boolean testTargetCanCreate() {
		// Find parent path
		String repoPathNoSlash = FileUtil.removeTrailingSlash(getSettings().getPath());
		int repoPathLastSlash = repoPathNoSlash.lastIndexOf("/");
		String parentPath = (repoPathLastSlash > 0) ? repoPathNoSlash.substring(0, repoPathLastSlash) : "/";

		// Test parent path permissions
		try {
			SmbFile parentSmbFolder = new SmbFile(URI.create(repoPath + "/" + parentPath + "/").normalize().toString(), authentication);

			if (parentSmbFolder.isDirectory()) {
				SmbFile testSmbFolder = new SmbFile(URI.create(repoPath + "/" + parentPath + "/" + "syncany-folder-test/").normalize().toString(), authentication);
				testSmbFolder.mkdirs();
				testSmbFolder.delete();

				logger.log(Level.INFO, "testTargetCanCreate: Can create target at " + parentPath);
				return true;
			}
			else {
				logger.log(Level.INFO, "testTargetCanWrite: Can NOT create target at" + parentSmbFolder.getPath());
				return false;
			}
		}
		catch (Exception e) {
			logger.log(Level.INFO, "testTargetCanWrite: Can NOT create target at " + parentPath + ".", e);
			return false;
		}
	}

	@Override
	public boolean testRepoFileExists() {
		try {
			SmbFile remoteRepoFile = createSmbFile(new SyncanyRemoteFile());

			if (remoteRepoFile.isFile()) {
				logger.log(Level.INFO, "testRepoFileExists: Repo file exists at " + remoteRepoFile);
				return true;
			}
			else {
				logger.log(Level.INFO, "testRepoFileExists: Repo file DOES NOT exist at " + remoteRepoFile);
				return false;
			}
		}
		catch (Exception e) {
			logger.log(Level.INFO, "testRepoFileExists: Exception when trying to check repo file existence.", e);
			return false;
		}
	}

	private SmbFile createSmbFile(RemoteFile remoteFile) throws MalformedURLException {
		if (remoteFile != null) {
			return new SmbFile(URI.create(repoPath + "/" +
				getSettings().getPath() + "/" +
				getRemoteFilePath(remoteFile.getClass()) + "/" +
				remoteFile.getName()).normalize().toString(), authentication);
		}
		else {
			return new SmbFile(URI.create(repoPath + "/" + getSettings().getPath()).toString(), authentication);
		}
	}

	private String getRemoteFilePath(Class<? extends RemoteFile> remoteFile) {
		if (remoteFile.equals(MultichunkRemoteFile.class)) {
			return multichunksPath;
		}
		else if (remoteFile.equals(DatabaseRemoteFile.class)) {
			return databasesPath;
		}
		else if (remoteFile.equals(ActionRemoteFile.class)) {
			return actionsPath;
		}
		else if (remoteFile.equals(TransactionRemoteFile.class)) {
			return transactionsPath;
		}
		else if (remoteFile.equals(TempRemoteFile.class)) {
			return tempPath;
		}
		else {
			return "";
		}
	}

	protected static class SambaRemoteFile extends RemoteFile {
		public SambaRemoteFile(String name) throws StorageException {
			super(name);
		}
	}
}
