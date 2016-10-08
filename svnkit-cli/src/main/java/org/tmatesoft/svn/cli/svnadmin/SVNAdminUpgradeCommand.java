/*
 * ====================================================================
 * Copyright (c) 2004-2011 TMate Software Ltd.  All rights reserved.
 *
 * This software is licensed as described in the file COPYING, which
 * you should have received as part of this distribution.  The terms
 * are also available at http://svnkit.com/license.html.
 * If newer versions of this license are posted there, you may use a
 * newer version instead, at your option.
 * ====================================================================
 */
package org.tmatesoft.svn.cli.svnadmin;

import java.util.Collection;

import org.tmatesoft.svn.core.SVNCancelException;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.wc.SVNEvent;
import org.tmatesoft.svn.core.wc.admin.ISVNAdminEventHandler;
import org.tmatesoft.svn.core.wc.admin.SVNAdminClient;
import org.tmatesoft.svn.core.wc.admin.SVNAdminEvent;
import org.tmatesoft.svn.core.wc.admin.SVNAdminEventAction;


/**
 * @version 1.3
 * @author  TMate Software Ltd.
 */
public class SVNAdminUpgradeCommand extends SVNAdminCommand implements ISVNAdminEventHandler {

    public SVNAdminUpgradeCommand() {
        super("upgrade", null);
    }

    protected Collection createSupportedOptions() {
        return null;
    }

    public void run() throws SVNException {
        SVNAdminClient client = getEnvironment().getClientManager().getAdminClient();
        client.doUpgrade(getLocalRepository());
        getEnvironment().getOut().println();
        getEnvironment().getOut().println("Upgrade completed.");
        
    }

    public void handleAdminEvent(SVNAdminEvent event, double progress) throws SVNException {
        if (event.getAction() == SVNAdminEventAction.UPGRADE) {
            getEnvironment().getOut().println("Repository lock acquired.");
            getEnvironment().getOut().println("Please wait; upgrading the repository may take some time...");
        }
    }

    public void handleEvent(SVNEvent event, double progress) throws SVNException {
    }

    public void checkCancelled() throws SVNCancelException {
        getEnvironment().checkCancelled();
    }

}
