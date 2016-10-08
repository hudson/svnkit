/*
 * ====================================================================
 * Copyright (c) 2004-2011 TMate Software Ltd.  All rights reserved.
 *
 * This software is licensed as described in the file COPYING, which
 * you should have received as part of this distribution.  The terms
 * are also available at http://svnkit.com/license.html
 * If newer versions of this license are posted there, you may use a
 * newer version instead, at your option.
 * ====================================================================
 */
package org.tmatesoft.svn.core.internal.io.fs;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLock;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNProperties;
import org.tmatesoft.svn.core.SVNProperty;
import org.tmatesoft.svn.core.SVNPropertyValue;
import org.tmatesoft.svn.core.SVNRevisionProperty;
import org.tmatesoft.svn.core.internal.util.SVNDate;
import org.tmatesoft.svn.core.internal.util.SVNHashSet;
import org.tmatesoft.svn.core.internal.util.SVNPathUtil;
import org.tmatesoft.svn.core.internal.wc.SVNErrorManager;
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil;
import org.tmatesoft.svn.core.io.ISVNLockHandler;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.util.SVNLogType;


/**
 * @version 1.3
 * @author  TMate Software Ltd.
 */
public class FSCommitter {

    private FSFS myFSFS;
    private FSTransactionRoot myTxnRoot;
    private FSTransactionInfo myTxn;
    private Collection myLockTokens;
    private String myAuthor;

    public FSCommitter(FSFS fsfs, FSTransactionRoot txnRoot, FSTransactionInfo txn, Collection lockTokens, String author) {
        myFSFS = fsfs;
        myTxnRoot = txnRoot;
        myTxn = txn;
        myLockTokens = lockTokens != null ? lockTokens : Collections.EMPTY_LIST;
        myAuthor = author;
    }

    public void reset(FSFS fsfs, FSTransactionRoot txnRoot, FSTransactionInfo txn, Collection lockTokens, String author) {
        myFSFS = fsfs;
        myTxnRoot = txnRoot;
        myTxn = txn;
        myLockTokens = lockTokens != null ? lockTokens : Collections.EMPTY_LIST;
        myAuthor = author;
    }
    
    public void deleteNode(String path) throws SVNException {
        FSParentPath parentPath = myTxnRoot.openPath(path, true, true);
        SVNNodeKind kind = parentPath.getRevNode().getType();
        if (parentPath.getParent() == null) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_ROOT_DIR, 
                    "The root directory cannot be deleted");
            SVNErrorManager.error(err, SVNLogType.FSFS);
        }

        if ((myTxnRoot.getTxnFlags() & FSTransactionRoot.SVN_FS_TXN_CHECK_LOCKS) != 0) {
            allowLockedOperation(myFSFS, path, myAuthor, myLockTokens, true, false);
        }

        makePathMutable(parentPath.getParent(), path);
        myTxnRoot.deleteEntry(parentPath.getParent().getRevNode(), parentPath.getEntryName());
        myTxnRoot.removeRevNodeFromCache(parentPath.getAbsPath());
        if (myFSFS.supportsMergeInfo()) {
            long mergeInfoCount = parentPath.getRevNode().getMergeInfoCount();
            if (mergeInfoCount > 0) {
                incrementMergeInfoUpTree(parentPath.getParent(), -mergeInfoCount);
            }
        }
        addChange(path, parentPath.getRevNode().getId(), FSPathChangeKind.FS_PATH_CHANGE_DELETE, false, false, SVNRepository.INVALID_REVISION, null, kind);
    }

    public void changeNodeProperty(String path, String name, SVNPropertyValue propValue) throws SVNException {
        FSRepositoryUtil.validateProperty(name, propValue);
        FSParentPath parentPath = myTxnRoot.openPath(path, true, true);
        SVNNodeKind kind = parentPath.getRevNode().getType();

        if ((myTxnRoot.getTxnFlags() & FSTransactionRoot.SVN_FS_TXN_CHECK_LOCKS) != 0) {
            FSCommitter.allowLockedOperation(myFSFS, path, myAuthor, myLockTokens, false, false);
        }

        makePathMutable(parentPath, path);
        SVNProperties properties = parentPath.getRevNode().getProperties(myFSFS);

        if (properties.isEmpty() && propValue == null) {
            return;
        }

        if (myFSFS.supportsMergeInfo() && name.equals(SVNProperty.MERGE_INFO)) {
            long increment = 0;
            boolean hadMergeInfo = parentPath.getRevNode().hasMergeInfo(); 
            if (propValue != null && !hadMergeInfo) {
                increment = 1;
            } else if (propValue == null && hadMergeInfo) {
                increment = -1;
            }
            if (increment != 0) {
                parentPath.getRevNode().setHasMergeInfo(propValue != null);
                incrementMergeInfoUpTree(parentPath, increment);
            }
        }
        
        if (propValue == null) {
            properties.remove(name);
        } else {
            properties.put(name, propValue);
        }

        myTxnRoot.setProplist(parentPath.getRevNode(), properties);
        addChange(path, parentPath.getRevNode().getId(), FSPathChangeKind.FS_PATH_CHANGE_MODIFY, false, true, SVNRepository.INVALID_REVISION, null, kind);
    }

    public void makeCopy(FSRevisionRoot fromRoot, String fromPath, String toPath, boolean preserveHistory) throws SVNException {
        String txnId = myTxnRoot.getTxnID();
        FSRevisionNode fromNode = fromRoot.getRevisionNode(fromPath);

        FSParentPath toParentPath = myTxnRoot.openPath(toPath, false, true);
        if ((myTxnRoot.getTxnFlags() & FSTransactionRoot.SVN_FS_TXN_CHECK_LOCKS) != 0) {
            FSCommitter.allowLockedOperation(myFSFS, toPath, myAuthor, myLockTokens, true, false);
        }

        if (toParentPath.getRevNode() != null && toParentPath.getRevNode().getId().equals(fromNode.getId())) {
            return;
        }

        FSPathChangeKind changeKind;
        long mergeInfoStart = 0;
        if (toParentPath.getRevNode() != null) {
            changeKind = FSPathChangeKind.FS_PATH_CHANGE_REPLACE;
            if (myFSFS.supportsMergeInfo()) {
                mergeInfoStart = toParentPath.getRevNode().getMergeInfoCount();
            }
        } else {
            changeKind = FSPathChangeKind.FS_PATH_CHANGE_ADD;
        }

        makePathMutable(toParentPath.getParent(), toPath);
        String fromCanonPath = SVNPathUtil.canonicalizeAbsolutePath(fromPath);
        copy(toParentPath.getParent().getRevNode(), toParentPath.getEntryName(), fromNode, preserveHistory, fromRoot.getRevision(), fromCanonPath, txnId);

        if (changeKind == FSPathChangeKind.FS_PATH_CHANGE_REPLACE) {
            myTxnRoot.removeRevNodeFromCache(toParentPath.getAbsPath());
        }
        
        long mergeInfoEnd = 0;
        if (myFSFS.supportsMergeInfo()) {
            mergeInfoEnd = fromNode.getMergeInfoCount();
            if (mergeInfoStart != mergeInfoEnd) {
                incrementMergeInfoUpTree(toParentPath.getParent(), mergeInfoEnd - mergeInfoStart);
            }
        }

        FSRevisionNode newNode = myTxnRoot.getRevisionNode(toPath);
        addChange(toPath, newNode.getId(), changeKind, false, false, fromRoot.getRevision(), fromCanonPath, fromNode.getType());
    }

    public void makeFile(String path) throws SVNException {
        SVNPathUtil.checkPathIsValid(path);
        String txnId = myTxnRoot.getTxnID();
        FSParentPath parentPath = myTxnRoot.openPath(path, false, true);

        if (parentPath.getRevNode() != null) {
            SVNErrorManager.error(FSErrors.errorAlreadyExists(myTxnRoot, path, myFSFS), SVNLogType.FSFS);
        }

        if ((myTxnRoot.getTxnFlags() & FSTransactionRoot.SVN_FS_TXN_CHECK_LOCKS) != 0) {
            FSCommitter.allowLockedOperation(myFSFS, path, myAuthor, myLockTokens, false, false);
        }

        makePathMutable(parentPath.getParent(), path);
        FSRevisionNode childNode = makeEntry(parentPath.getParent().getRevNode(), parentPath.getParent().getAbsPath(), parentPath.getEntryName(), false, txnId);

        myTxnRoot.putRevNodeToCache(parentPath.getAbsPath(), childNode);
        addChange(path, childNode.getId(), FSPathChangeKind.FS_PATH_CHANGE_ADD, false, false, SVNRepository.INVALID_REVISION, null, SVNNodeKind.FILE);
    }

    public void makeDir(String path) throws SVNException {
        SVNPathUtil.checkPathIsValid(path);
        String txnId = myTxnRoot.getTxnID();
        FSParentPath parentPath = myTxnRoot.openPath(path, false, true);

        if (parentPath.getRevNode() != null) {
            SVNErrorManager.error(FSErrors.errorAlreadyExists(myTxnRoot, path, myFSFS), SVNLogType.FSFS);
        }

        if ((myTxnRoot.getTxnFlags() & FSTransactionRoot.SVN_FS_TXN_CHECK_LOCKS) != 0) {
            FSCommitter.allowLockedOperation(myFSFS, path, myAuthor, myLockTokens, true, false);
        }

        makePathMutable(parentPath.getParent(), path);
        FSRevisionNode subDirNode = makeEntry(parentPath.getParent().getRevNode(), parentPath.getParent().getAbsPath(), parentPath.getEntryName(), true, txnId);

        myTxnRoot.putRevNodeToCache(parentPath.getAbsPath(), subDirNode);
        addChange(path, subDirNode.getId(), FSPathChangeKind.FS_PATH_CHANGE_ADD, false, false, SVNRepository.INVALID_REVISION, null, SVNNodeKind.DIR);
    }

    public FSRevisionNode makeEntry(FSRevisionNode parent, String parentPath, String entryName, boolean isDir, String txnId) throws SVNException {
        if (!SVNPathUtil.isSinglePathComponent(entryName)) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NOT_SINGLE_PATH_COMPONENT, "Attempted to create a node with an illegal name ''{0}''", entryName);
            SVNErrorManager.error(err, SVNLogType.FSFS);
        }

        if (parent.getType() != SVNNodeKind.DIR) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NOT_DIRECTORY, "Attempted to create entry in non-directory parent");
            SVNErrorManager.error(err, SVNLogType.FSFS);
        }

        if (!parent.getId().isTxn()) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NOT_MUTABLE, "Attempted to clone child of non-mutable node");
            SVNErrorManager.error(err, SVNLogType.FSFS);
        }

        FSRevisionNode newRevNode = new FSRevisionNode();
        newRevNode.setType(isDir ? SVNNodeKind.DIR : SVNNodeKind.FILE);
        String createdPath = SVNPathUtil.getAbsolutePath(SVNPathUtil.append(parentPath, entryName));
        newRevNode.setCreatedPath(createdPath);
        newRevNode.setCopyRootPath(parent.getCopyRootPath());
        newRevNode.setCopyRootRevision(parent.getCopyRootRevision());
        newRevNode.setCopyFromRevision(SVNRepository.INVALID_REVISION);
        newRevNode.setCopyFromPath(null);
        FSID newNodeId = createNode(newRevNode, parent.getId().getCopyID(), txnId);

        FSRevisionNode childNode = myFSFS.getRevisionNode(newNodeId);

        myTxnRoot.setEntry(parent, entryName, childNode.getId(), newRevNode.getType());
        return childNode;
    }

    public void addChange(String path, FSID id, FSPathChangeKind changeKind, boolean textModified, 
            boolean propsModified, long copyFromRevision, String copyFromPath, SVNNodeKind kind) throws SVNException {
        path = SVNPathUtil.canonicalizeAbsolutePath(path);
        OutputStream changesFile = null;
        try {
            changesFile = SVNFileUtil.openFileForWriting(myTxnRoot.getTransactionChangesFile(), true);
            FSPathChange pathChange = new FSPathChange(path, id, changeKind, textModified, propsModified, copyFromPath, copyFromRevision, kind);
            myTxnRoot.writeChangeEntry(changesFile, pathChange, true);
        } catch (IOException ioe) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, ioe.getLocalizedMessage());
            SVNErrorManager.error(err, ioe, SVNLogType.FSFS);
        } finally {
            SVNFileUtil.closeFile(changesFile);
        }
    }

    public long commitTxn(boolean runPreCommitHook, boolean runPostCommitHook, SVNErrorMessage[] postCommitHookError, StringBuffer conflictPath) throws SVNException {
        if (runPreCommitHook) {
            FSHooks.runPreCommitHook(myFSFS.getRepositoryRoot(), myTxn.getTxnId());
        }

        long newRevision = SVNRepository.INVALID_REVISION;

        while (true) {
            long youngishRev = myFSFS.getYoungestRevision();
            FSRevisionRoot youngishRoot = myFSFS.createRevisionRoot(youngishRev);

            FSRevisionNode youngishRootNode = youngishRoot.getRevisionNode("/");

            mergeChanges(null, youngishRootNode, conflictPath);

            myTxn.setBaseRevision(youngishRev);

            FSWriteLock writeLock = FSWriteLock.getWriteLockForDB(myFSFS);
            synchronized (writeLock) {
                try {
                    writeLock.lock();
                    newRevision = commit();
                } catch (SVNException svne) {
                    if (svne.getErrorMessage().getErrorCode() == SVNErrorCode.FS_TXN_OUT_OF_DATE) {
                        long youngestRev = myFSFS.getYoungestRevision();
                        if (youngishRev == youngestRev) {
                            throw svne;
                        }
                        continue;
                    }
                    throw svne;
                } finally {
                    writeLock.unlock();
                    FSWriteLock.release(writeLock);
                }
            }
            break;
        }
        
        if (runPostCommitHook) {
            try {
                FSHooks.runPostCommitHook(myFSFS.getRepositoryRoot(), newRevision);
             } catch (SVNException svne) {
                 SVNErrorMessage errorMessage = SVNErrorMessage.create(SVNErrorCode.REPOS_POST_COMMIT_HOOK_FAILED, 
                         "Commit succeeded, but post-commit hook failed", SVNErrorMessage.TYPE_WARNING);
                 errorMessage.setChildErrorMessage(svne.getErrorMessage());
                 
                 if (postCommitHookError != null && postCommitHookError.length > 0) {
                     postCommitHookError[0] = errorMessage;
                 } else {
                     SVNErrorManager.error(errorMessage, SVNLogType.FSFS);
                 }
             }
        }
        return newRevision;
    }

    public void makePathMutable(FSParentPath parentPath, String errorPath) throws SVNException {
        String txnId = myTxnRoot.getTxnID();

        if (parentPath.getRevNode().getId().isTxn()) {
            return;
        }
        FSRevisionNode clone = null;

        if (parentPath.getParent() != null) {
            makePathMutable(parentPath.getParent(), errorPath);
            FSID parentId = null;
            String copyId = null;

            switch (parentPath.getCopyStyle()) {
                case FSCopyInheritance.COPY_ID_INHERIT_PARENT:
                    parentId = parentPath.getParent().getRevNode().getId();
                    copyId = parentId.getCopyID();
                    break;
                case FSCopyInheritance.COPY_ID_INHERIT_NEW:
                    copyId = reserveCopyId(txnId);
                    break;
                case FSCopyInheritance.COPY_ID_INHERIT_SELF:
                    copyId = null;
                    break;
                case FSCopyInheritance.COPY_ID_INHERIT_UNKNOWN:
                default:
                    SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNKNOWN, "FATAL error: can not make path ''{0}'' mutable", errorPath);
                    SVNErrorManager.error(err, SVNLogType.FSFS);
            }

            String copyRootPath = parentPath.getRevNode().getCopyRootPath();
            long copyRootRevision = parentPath.getRevNode().getCopyRootRevision();

            FSRoot copyrootRoot = myFSFS.createRevisionRoot(copyRootRevision);
            FSRevisionNode copyRootNode = copyrootRoot.getRevisionNode(copyRootPath);
            FSID childId = parentPath.getRevNode().getId();
            FSID copyRootId = copyRootNode.getId();
            boolean isParentCopyRoot = false;
            if (!childId.getNodeID().equals(copyRootId.getNodeID())) {
                isParentCopyRoot = true;
            }

            String clonePath = parentPath.getParent().getAbsPath();
            clone = myTxnRoot.cloneChild(parentPath.getParent().getRevNode(), clonePath, parentPath.getEntryName(), copyId, isParentCopyRoot);

            myTxnRoot.putRevNodeToCache(parentPath.getAbsPath(), clone);
        } else {
            FSTransactionInfo txn = myTxnRoot.getTxn();

            if (txn.getRootID().equals(txn.getBaseID())) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNKNOWN, 
                                                             "FATAL error: txn ''{0}'' root id ''{1}'' matches base id ''{2}''", 
                                                             new Object[] { txnId, txn.getRootID(), txn.getBaseID() });
                SVNErrorManager.error(err, SVNLogType.FSFS);
            }
            clone = myFSFS.getRevisionNode(txn.getRootID());
        }

        parentPath.setRevNode(clone);
    }

    public String reserveCopyId(String txnId) throws SVNException {
        String[] nextIds = myTxnRoot.readNextIDs();
        String copyId = FSRepositoryUtil.generateNextKey(nextIds[1]);
        myFSFS.writeNextIDs(txnId, nextIds[0], copyId);
        return "_" + nextIds[1];
    }

    public void incrementMergeInfoUpTree(FSParentPath parentPath, long increment) throws SVNException {
        while (parentPath != null) {
            myTxnRoot.incrementMergeInfoCount(parentPath.getRevNode(), increment);
            parentPath = parentPath.getParent();
        }
    }
    
    private void copy(FSRevisionNode toNode, String entryName, FSRevisionNode fromNode, boolean preserveHistory, 
            long fromRevision, String fromPath, String txnId) throws SVNException {
        FSID id = null;
        if (preserveHistory) {
            FSID srcId = fromNode.getId();
            FSRevisionNode toRevNode = FSRevisionNode.dumpRevisionNode(fromNode);
            String copyId = reserveCopyId(txnId);

            toRevNode.setPredecessorId(srcId);
            if (toRevNode.getCount() != -1) {
                toRevNode.setCount(toRevNode.getCount() + 1);
            }
            String createdPath = SVNPathUtil.getAbsolutePath(SVNPathUtil.append(toNode.getCreatedPath(), entryName));
            toRevNode.setCreatedPath(createdPath);
            toRevNode.setCopyFromPath(fromPath);
            toRevNode.setCopyFromRevision(fromRevision);

            toRevNode.setCopyRootPath(null);
            id = myTxnRoot.createSuccessor(srcId, toRevNode, copyId);
        } else {
            id = fromNode.getId();
        }

        myTxnRoot.setEntry(toNode, entryName, id, fromNode.getType());
    }

    private FSID createNode(FSRevisionNode revNode, String copyId, String txnId) throws SVNException {
        String nodeId = myTxnRoot.getNewTxnNodeId();
        FSID id = FSID.createTxnId(nodeId, copyId, txnId);
        revNode.setId(id);
        revNode.setIsFreshTxnRoot(false);
        myFSFS.putTxnRevisionNode(id, revNode);
        return id;
    }

    private long commit() throws SVNException {
        long oldRev = myFSFS.getYoungestRevision();

        if (myTxn.getBaseRevision() != oldRev) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_TXN_OUT_OF_DATE, "Transaction out of date");
            SVNErrorManager.error(err, SVNLogType.FSFS);
        }

        verifyLocks();

        final String startNodeId;
        final String startCopyId;
        if (myFSFS.getDBFormat() < FSFS.MIN_NO_GLOBAL_IDS_FORMAT) {
            String[] ids = myFSFS.getNextRevisionIDs();
            startNodeId = ids[0];
            startCopyId = ids[1];
        } else {
            startNodeId = null;
            startCopyId = null;
        }

        final long newRevision = oldRev + 1;
        final OutputStream protoFileOS = null;
        final FSID newRootId = null;
        final FSWriteLock txnWriteLock = FSWriteLock.getWriteLockForTxn(myTxn.getTxnId(), myFSFS);
        synchronized (txnWriteLock) {
            try {
                // start transaction.
                txnWriteLock.lock();
                final File revisionPrototypeFile = myTxnRoot.getTransactionProtoRevFile();
                final long offset = revisionPrototypeFile.length();
                if (myFSFS.getRepositoryCacheManager() != null) {
                    myFSFS.getRepositoryCacheManager().runWriteTransaction(new IFSSqlJetTransaction() {
                        public void run() throws SVNException {
                            commit(startNodeId, startCopyId, newRevision, protoFileOS, newRootId, myTxnRoot, revisionPrototypeFile, offset);
                        }
                    });
                } else {
                    commit(startNodeId, startCopyId, newRevision, protoFileOS, newRootId, myTxnRoot, revisionPrototypeFile, offset);
                }
                File dstRevFile = myFSFS.getNewRevisionFile(newRevision);
                SVNFileUtil.rename(revisionPrototypeFile, dstRevFile);
            } finally {
               txnWriteLock.unlock();
               FSWriteLock.release(txnWriteLock);
            }
        }
        
        String commitTime = SVNDate.formatDate(new Date(System.currentTimeMillis()));
        myFSFS.setTransactionProperty(myTxn.getTxnId(), SVNRevisionProperty.DATE, 
                SVNPropertyValue.create(commitTime));
        
        File txnPropsFile = myFSFS.getTransactionPropertiesFile(myTxn.getTxnId());
        File dstRevPropsFile = myFSFS.getNewRevisionPropertiesFile(newRevision);
        SVNFileUtil.rename(txnPropsFile, dstRevPropsFile);

        try {
            myTxnRoot.writeFinalCurrentFile(newRevision, startNodeId, startCopyId);
        } catch (IOException ioe) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, ioe.getLocalizedMessage());
            SVNErrorManager.error(err, ioe, SVNLogType.FSFS);
        }
        myFSFS.setYoungestRevisionCache(newRevision);
        myFSFS.purgeTxn(myTxn.getTxnId());
        return newRevision;
    }

    private void commit(String startNodeId, String startCopyId, long newRevision, OutputStream protoFileOS, FSID newRootId, FSTransactionRoot txnRoot, File revisionPrototypeFile, long offset)
            throws SVNException {
        try {
            protoFileOS = SVNFileUtil.openFileForWriting(revisionPrototypeFile, true);
            FSID rootId = FSID.createTxnId("0", "0", myTxn.getTxnId());

            CountingOutputStream revWriter = new CountingOutputStream(protoFileOS, offset);
            newRootId = txnRoot.writeFinalRevision(newRootId, revWriter, newRevision, rootId, 
                    startNodeId, startCopyId);
            long changedPathOffset = txnRoot.writeFinalChangedPathInfo(revWriter);

            String offsetsLine = "\n" + newRootId.getOffset() + " " + changedPathOffset + "\n";
            protoFileOS.write(offsetsLine.getBytes("UTF-8"));
        } catch (IOException ioe) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, ioe.getLocalizedMessage());
            SVNErrorManager.error(err, ioe, SVNLogType.FSFS);
        } finally {
            SVNFileUtil.closeFile(protoFileOS);
        }

        SVNProperties txnProps = myFSFS.getTransactionProperties(myTxn.getTxnId());
        if (txnProps != null && !txnProps.isEmpty()) {
            if (txnProps.getStringValue(SVNProperty.TXN_CHECK_OUT_OF_DATENESS) != null) {
                myFSFS.setTransactionProperty(myTxn.getTxnId(), SVNProperty.TXN_CHECK_OUT_OF_DATENESS, null);
            }
            if (txnProps.getStringValue(SVNProperty.TXN_CHECK_LOCKS) != null) {
                myFSFS.setTransactionProperty(myTxn.getTxnId(), SVNProperty.TXN_CHECK_LOCKS, null);
            }
        }
    }

    private void mergeChanges(FSRevisionNode ancestorNode, FSRevisionNode sourceNode, StringBuffer conflictPath) throws SVNException {
        String txnId = myTxn.getTxnId();
        FSRevisionNode txnRootNode = myTxnRoot.getRootRevisionNode();

        if (ancestorNode == null) {
            ancestorNode = myTxnRoot.getTxnBaseRootNode();
        }

        if (txnRootNode.getId().equals(ancestorNode.getId())) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNKNOWN, "FATAL error: no changes in transaction to commit");
            SVNErrorManager.error(err, SVNLogType.FSFS);
        } else {
            merge("/", txnRootNode, sourceNode, ancestorNode, txnId, conflictPath);
        }
    }

    private long merge(String targetPath, FSRevisionNode target, FSRevisionNode source, FSRevisionNode ancestor, String txnId, 
            StringBuffer conflictPath) throws SVNException {
        FSID sourceId = source.getId();
        FSID targetId = target.getId();
        FSID ancestorId = ancestor.getId();
        long mergeInfoIncrement = 0;
        
        if (ancestorId.equals(targetId)) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_GENERAL, "Bad merge; target ''{0}'' has id ''{1}'', same as ancestor", new Object[] {
                    targetPath, targetId
            });
            SVNErrorManager.error(err, SVNLogType.FSFS);
        }

        if (ancestorId.equals(sourceId) || sourceId.equals(targetId)) {
            return mergeInfoIncrement;
        }

        if (source.getType() != SVNNodeKind.DIR || target.getType() != SVNNodeKind.DIR || ancestor.getType() != SVNNodeKind.DIR) {
            SVNErrorManager.error(FSErrors.errorConflict(targetPath, conflictPath), SVNLogType.FSFS);
        }

        if (!FSRepresentation.compareRepresentations(target.getPropsRepresentation(), ancestor.getPropsRepresentation())) {
            SVNErrorManager.error(FSErrors.errorConflict(targetPath, conflictPath), SVNLogType.FSFS);
        }

        Map sourceEntries = source.getDirEntries(myFSFS);
        Map targetEntries = target.getDirEntries(myFSFS);
        Map ancestorEntries = ancestor.getDirEntries(myFSFS);
        Set removedEntries = new SVNHashSet();
        for (Iterator ancestorEntryNames = ancestorEntries.keySet().iterator(); ancestorEntryNames.hasNext();) {
            String ancestorEntryName = (String) ancestorEntryNames.next();
            FSEntry ancestorEntry = (FSEntry) ancestorEntries.get(ancestorEntryName);
            FSEntry sourceEntry = removedEntries.contains(ancestorEntryName) ? null : (FSEntry) sourceEntries.get(ancestorEntryName);
            FSEntry targetEntry = (FSEntry) targetEntries.get(ancestorEntryName);
            if (sourceEntry != null && ancestorEntry.getId().equals(sourceEntry.getId())) {
                /*
                 * No changes were made to this entry while the transaction was
                 * in progress, so do nothing to the target.
                 */
            } else if (targetEntry != null && ancestorEntry.getId().equals(targetEntry.getId())) {
                if (myFSFS.supportsMergeInfo()) {
                    FSRevisionNode targetEntryNode = myFSFS.getRevisionNode(targetEntry.getId());
                    long mergeInfoStart = targetEntryNode.getMergeInfoCount();
                    mergeInfoIncrement -= mergeInfoStart;
                }
                if (sourceEntry != null) {
                    if (myFSFS.supportsMergeInfo()) {
                        FSRevisionNode sourceEntryNode = myFSFS.getRevisionNode(sourceEntry.getId());
                        long mergeInfoEnd = sourceEntryNode.getMergeInfoCount();
                        mergeInfoIncrement += mergeInfoEnd;
                    }
                    myTxnRoot.setEntry(target, ancestorEntryName, sourceEntry.getId(), sourceEntry.getType());
                } else {
                    myTxnRoot.deleteEntry(target, ancestorEntryName);
                }
            } else {
                
                if (sourceEntry == null || targetEntry == null) {
                    SVNErrorManager.error(FSErrors.errorConflict(SVNPathUtil.getAbsolutePath(SVNPathUtil.append(targetPath, ancestorEntryName)), 
                            conflictPath), SVNLogType.FSFS);
                }

                if (sourceEntry.getType() == SVNNodeKind.FILE || targetEntry.getType() == SVNNodeKind.FILE || ancestorEntry.getType() == SVNNodeKind.FILE) {
                    SVNErrorManager.error(FSErrors.errorConflict(SVNPathUtil.getAbsolutePath(SVNPathUtil.append(targetPath, ancestorEntryName)), 
                            conflictPath), SVNLogType.FSFS);
                }

                if (!sourceEntry.getId().getNodeID().equals(ancestorEntry.getId().getNodeID()) || 
                        !sourceEntry.getId().getCopyID().equals(ancestorEntry.getId().getCopyID()) || 
                        !targetEntry.getId().getNodeID().equals(ancestorEntry.getId().getNodeID()) || 
                        !targetEntry.getId().getCopyID().equals(ancestorEntry.getId().getCopyID())) {
                    SVNErrorManager.error(FSErrors.errorConflict(SVNPathUtil.getAbsolutePath(SVNPathUtil.append(targetPath, ancestorEntryName)), 
                            conflictPath), SVNLogType.FSFS);
                }

                FSRevisionNode sourceEntryNode = myFSFS.getRevisionNode(sourceEntry.getId());
                FSRevisionNode targetEntryNode = myFSFS.getRevisionNode(targetEntry.getId());
                FSRevisionNode ancestorEntryNode = myFSFS.getRevisionNode(ancestorEntry.getId());
                String childTargetPath = SVNPathUtil.getAbsolutePath(SVNPathUtil.append(targetPath, targetEntry.getName()));
                long subMergeInfoIncrement = merge(childTargetPath, targetEntryNode, sourceEntryNode, ancestorEntryNode, txnId, conflictPath);
                if (myFSFS.supportsMergeInfo()) {
                    mergeInfoIncrement += subMergeInfoIncrement;
                }
            }

            removedEntries.add(ancestorEntryName);
        }

        for (Iterator sourceEntryNames = sourceEntries.keySet().iterator(); sourceEntryNames.hasNext();) {
            String sourceEntryName = (String) sourceEntryNames.next();
            if (removedEntries.contains(sourceEntryName)){
                continue;                
            }
            FSEntry sourceEntry = (FSEntry) sourceEntries.get(sourceEntryName);
            FSEntry targetEntry = (FSEntry) targetEntries.get(sourceEntryName);
            if (targetEntry != null) {
                SVNErrorManager.error(FSErrors.errorConflict(SVNPathUtil.getAbsolutePath(SVNPathUtil.append(targetPath, targetEntry.getName())), 
                        conflictPath), SVNLogType.FSFS);
            }
            if (myFSFS.supportsMergeInfo()) {
                FSRevisionNode sourceEntryNode = myFSFS.getRevisionNode(sourceEntry.getId());
                long mergeInfoCount = sourceEntryNode.getMergeInfoCount();
                mergeInfoIncrement += mergeInfoCount;
            }
            myTxnRoot.setEntry(target, sourceEntry.getName(), sourceEntry.getId(), sourceEntry.getType());
        }
        long sourceCount = source.getCount();
        updateAncestry(sourceId, targetId, targetPath, sourceCount);
        if (myFSFS.supportsMergeInfo()) {
            myTxnRoot.incrementMergeInfoCount(target, mergeInfoIncrement);
        }
        return mergeInfoIncrement;
    }

    private void updateAncestry(FSID sourceId, FSID targetId, String targetPath, long sourcePredecessorCount) throws SVNException {
        if (!targetId.isTxn()) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NOT_MUTABLE, "Unexpected immutable node at ''{0}''", targetPath);
            SVNErrorManager.error(err, SVNLogType.FSFS);
        }
        FSRevisionNode revNode = myFSFS.getRevisionNode(targetId);
        revNode.setPredecessorId(sourceId);
        revNode.setCount(sourcePredecessorCount != -1 ? sourcePredecessorCount + 1 : sourcePredecessorCount);
        revNode.setIsFreshTxnRoot(false);
        myFSFS.putTxnRevisionNode(targetId, revNode);
    }

    private void verifyLocks() throws SVNException {
        Map changes = myTxnRoot.getChangedPaths();
        Object[] changedPaths = changes.keySet().toArray();
        Arrays.sort(changedPaths);

        String lastRecursedPath = null;
        for (int i = 0; i < changedPaths.length; i++) {
            String changedPath = (String) changedPaths[i];
            boolean recurse = true;

            if (lastRecursedPath != null && SVNPathUtil.getPathAsChild(lastRecursedPath, changedPath) != null) {
                continue;
            }

            FSPathChange change = (FSPathChange) changes.get(changedPath);

            if (change.getChangeKind() == FSPathChangeKind.FS_PATH_CHANGE_MODIFY) {
                recurse = false;
            }
            allowLockedOperation(myFSFS, changedPath, myAuthor, myLockTokens, recurse, true);

            if (recurse) {
                lastRecursedPath = changedPath;
            }
        }
    }

    public static void allowLockedOperation(FSFS fsfs, String path, final String username, final Collection lockTokens, boolean recursive, boolean haveWriteLock) throws SVNException {
        path = SVNPathUtil.canonicalizeAbsolutePath(path);
        if (recursive) {
            ISVNLockHandler handler = new ISVNLockHandler() {

                private String myUsername = username;
                private Collection myTokens = lockTokens;

                public void handleLock(String path, SVNLock lock, SVNErrorMessage error) throws SVNException {
                    verifyLock(lock, myTokens, myUsername);
                }

                public void handleUnlock(String path, SVNLock lock, SVNErrorMessage error) throws SVNException {
                }
            };
            fsfs.walkDigestFiles(fsfs.getDigestFileFromRepositoryPath(path), handler, haveWriteLock);
        } else {
            SVNLock lock = fsfs.getLockHelper(path, haveWriteLock);
            if (lock != null) {
                verifyLock(lock, lockTokens, username);
            }
        }
    }
    
    private static void verifyLock(SVNLock lock, Collection lockTokens, String username) throws SVNException {
        if (username == null || "".equals(username)) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NO_USER, "Cannot verify lock on path ''{0}''; no username available", lock.getPath());
            SVNErrorManager.error(err, SVNLogType.FSFS);
        } else if (username.compareTo(lock.getOwner()) != 0) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_LOCK_OWNER_MISMATCH, "User {0} does not own lock on path ''{1}'' (currently locked by {2})", new Object[] {
                    username, lock.getPath(), lock.getOwner()
            });
            SVNErrorManager.error(err, SVNLogType.FSFS);
        }
        
        if (lockTokens.contains(lock.getID())) {
            return;
        }
        
        SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_BAD_LOCK_TOKEN, "Cannot verify lock on path ''{0}''; no matching lock-token available", lock.getPath());
        SVNErrorManager.error(err, SVNLogType.FSFS);
    }

    public static void abortTransaction(FSFS fsfs, String txnId) throws SVNException {
        File txnDir = fsfs.getTransactionDir(txnId);
        SVNFileUtil.deleteAll(txnDir, true);
        if (txnDir.exists()) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNKNOWN, "Transaction cleanup failed");
            SVNErrorManager.error(err, SVNLogType.FSFS);
        }
    }

}
