package com.monolith.core.vfs

import com.monolith.core.common.FolderHiddenRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderHiddenRulesTest {

    @Test
    fun inheritsParentRuleIntoSubfoldersAndOverridesSpecificChild() {
        val rules = mutableMapOf<String, Boolean>()
        FolderHiddenRules.setShowHidden(rules, "/storage/emulated/0/Download", true)

        assertTrue(FolderHiddenRules.isShowHidden(rules, "/storage/emulated/0/Download"))
        assertTrue(FolderHiddenRules.isShowHidden(rules, "/storage/emulated/0/Download/Sub/Deep"))
        assertFalse(FolderHiddenRules.isShowHidden(rules, "/storage/emulated/0/Pictures"))

        FolderHiddenRules.setShowHidden(rules, "/storage/emulated/0/Download/Sub", false)
        assertFalse(FolderHiddenRules.isShowHidden(rules, "/storage/emulated/0/Download/Sub/Deep"))
        assertTrue(FolderHiddenRules.isShowHidden(rules, "/storage/emulated/0/Download"))

        // Toggling parent propagates by clearing child overrides
        FolderHiddenRules.setShowHidden(rules, "/storage/emulated/0/Download", false)
        assertFalse(FolderHiddenRules.isShowHidden(rules, "/storage/emulated/0/Download/Sub/Deep"))
    }

    @Test
    fun supportsRemoteAncestorStackAndSmbPaths() {
        val rules = mutableMapOf<String, Boolean>()
        FolderHiddenRules.setShowHidden(rules, "gdrive://folderA", true)

        assertTrue(
            FolderHiddenRules.isShowHidden(
                rules = rules,
                path = "gdrive://childB",
                ancestorPaths = listOf("gdrive://root", "gdrive://folderA", "gdrive://childB"),
            ),
        )

        FolderHiddenRules.setShowHidden(rules, "smb://srv1/Media/", true)
        assertTrue(FolderHiddenRules.isShowHidden(rules, "smb://srv1/Media/Movies/"))
        assertFalse(FolderHiddenRules.isShowHidden(rules, "smb://srv1/Other/"))
    }
}
