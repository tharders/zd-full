/*
 * 
 */
package com.zimbra.qa.unittest;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.List;

import junit.framework.TestCase;

import com.zimbra.cs.mailbox.Document;
import com.zimbra.cs.mailbox.MailItem;
import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.mailbox.WikiItem;
import com.zimbra.cs.mime.ParsedDocument;
import com.zimbra.cs.store.MailboxBlob;
import com.zimbra.cs.store.StoreManager;
import com.zimbra.cs.store.file.Volume;

public class TestDocument extends TestCase {

    private static final String NAME_PREFIX = TestDocument.class.getSimpleName();
    private static final String USER_NAME = "user1";
    
    private long mOriginalCompressionThreshold;
    private boolean mOriginalCompressBlobs;
    
    @Override public void setUp()
    throws Exception {
        cleanUp();
        
        Volume vol = Volume.getCurrentMessageVolume();
        mOriginalCompressBlobs = vol.getCompressBlobs();
        mOriginalCompressionThreshold = vol.getCompressionThreshold();
    }
    
    /**
     * Server-side test that confirms that all blobs are cleaned up when
     * a document with multiple revisions is deleted.
     */
    public void testDeleteRevisions()
    throws Exception {
        Mailbox mbox = TestUtil.getMailbox(USER_NAME);
        
        // Create first revision.
        String content = "one";
        ParsedDocument pd = new ParsedDocument(
            new ByteArrayInputStream(content.getBytes()), NAME_PREFIX + "-testDeleteRevisions.txt", "text/plain", System.currentTimeMillis(), USER_NAME, "one", true);
        Document doc = mbox.createDocument(null, Mailbox.ID_FOLDER_BRIEFCASE, pd, MailItem.TYPE_DOCUMENT);
        int docId = doc.getId();
        byte type = doc.getType();
        File blobDir = getBlobDir(mbox, doc);
        List<Document> revisions = mbox.getAllRevisions(null, docId, type);
        assertEquals(1, revisions.size());
        assertEquals(1, getBlobCount(blobDir, docId));
        assertEquals(true, doc.isDescriptionEnabled());
        
        // Add a second revision.
        content = "two";
        pd = new ParsedDocument(
            new ByteArrayInputStream(content.getBytes()), NAME_PREFIX + "-testDeleteRevisions2.txt", "text/plain", System.currentTimeMillis(), USER_NAME, "two", false);
        doc = mbox.addDocumentRevision(null, docId, pd);
        assertEquals(2, mbox.getAllRevisions(null, docId, type).size());
        assertEquals(2, getBlobCount(blobDir, docId));
        assertEquals(false, doc.isDescriptionEnabled());
        
        // Move to trash, empty trash, and confirm that both blobs were deleted.
        mbox.move(null, doc.getId(), doc.getType(), Mailbox.ID_FOLDER_TRASH);
        mbox.emptyFolder(null, Mailbox.ID_FOLDER_TRASH, false);
        mbox.emptyDumpster(null);
        assertEquals(0, getBlobCount(blobDir, docId));
    }
    
    private int getBlobCount(File dir, int id)
    throws Exception {
        int count = 0;
        String prefix = id + "-";
        for (File file : dir.listFiles()) {
            if (file.getName().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }
    
    private File getBlobDir(Mailbox mbox, Document doc)
    throws Exception {
        MailboxBlob mblob = StoreManager.getInstance().getMailboxBlob(doc);
        File blobFile = mblob.getLocalBlob().getFile();
        return blobFile.getParentFile();
    }
    
    /**
     * Confirms that saving a document to a compressed volume works correctly (bug 48363). 
     */
    public void testCompressedVolume()
    throws Exception {
        Volume vol = Volume.getCurrentMessageVolume();
        Volume.update(vol.getId(), vol.getType(), vol.getName(), vol.getRootPath(),
            vol.getMboxGroupBits(), vol.getMboxBits(), vol.getFileGroupBits(), vol.getFileBits(),
            true, 1);
        String content = "<wiklet class='TOC' format=\"template\" bodyTemplate=\"_TocBodyTemplate\" itemTemplate=\"_TocItemTemplate\">abc</wiklet>";
        Mailbox mbox = TestUtil.getMailbox(USER_NAME);
        WikiItem wiki = mbox.createWiki(null, Mailbox.ID_FOLDER_BRIEFCASE, NAME_PREFIX + "-testCompressedVolume",
            "Unit Test", null, new ByteArrayInputStream(content.getBytes()));
        assertEquals("abc", wiki.getFragment());
    }
    
    @Override public void tearDown()
    throws Exception {
        cleanUp();
    }
    
    private void cleanUp()
    throws Exception {
        TestUtil.deleteTestData(USER_NAME, NAME_PREFIX);
        
        // Delete documents.
        Mailbox mbox = TestUtil.getMailbox(USER_NAME);
        for (MailItem item : mbox.getItemList(null, MailItem.TYPE_DOCUMENT)) {
            if (item.getName().contains(NAME_PREFIX)) {
                mbox.delete(null, item.getId(), item.getType());
            }
        }
        
        // Restore volume compression settings.
        Volume vol = Volume.getCurrentMessageVolume();
        Volume.update(vol.getId(), vol.getType(), vol.getName(), vol.getRootPath(),
            vol.getMboxGroupBits(), vol.getMboxBits(), vol.getFileGroupBits(), vol.getFileBits(),
            mOriginalCompressBlobs, mOriginalCompressionThreshold);
    }
}
