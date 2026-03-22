package com.kubefn.runtime.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RevisionManagerTest {

    private RevisionManager manager;

    @BeforeEach
    void setUp() {
        manager = new RevisionManager();
    }

    @Test
    void registerAndSelectRevision() {
        manager.registerRevision("grp", "rev-1", RevisionState.ACTIVE);

        String selected = manager.selectRevision("grp");
        assertEquals("rev-1", selected);
    }

    @Test
    void latestRevisionSelectedByDefault() {
        manager.registerRevision("grp", "rev-1", RevisionState.ACTIVE);
        manager.registerRevision("grp", "rev-2", RevisionState.ACTIVE);

        // rev-2 is latest, should get 100% weight
        assertEquals("rev-2", manager.getLatestRevision("grp"));
    }

    @Test
    void weightedTrafficSplit() {
        manager.registerRevision("grp", "rev-1", RevisionState.ACTIVE);
        manager.registerRevision("grp", "rev-2", RevisionState.ACTIVE);

        // Set 50/50 split
        manager.setWeight("rev-1", 50);
        manager.setWeight("rev-2", 50);

        // Run 100 selections — both should get traffic
        int rev1Count = 0, rev2Count = 0;
        for (int i = 0; i < 100; i++) {
            String selected = manager.selectRevision("grp");
            if ("rev-1".equals(selected)) rev1Count++;
            if ("rev-2".equals(selected)) rev2Count++;
        }

        assertTrue(rev1Count > 10, "rev-1 should get some traffic");
        assertTrue(rev2Count > 10, "rev-2 should get some traffic");
    }

    @Test
    void removeRevision() {
        manager.registerRevision("grp", "rev-1", RevisionState.ACTIVE);
        manager.registerRevision("grp", "rev-2", RevisionState.ACTIVE);

        manager.removeRevision("grp", "rev-1");

        var revisions = manager.getRevisions("grp");
        assertEquals(1, revisions.size());
        assertEquals("rev-2", revisions.get(0).revisionId());
    }

    @Test
    void selectFromEmptyGroupReturnsNull() {
        assertNull(manager.selectRevision("nonexistent"));
    }

    @Test
    void invalidWeightThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.setWeight("rev-1", -1));
        assertThrows(IllegalArgumentException.class,
                () -> manager.setWeight("rev-1", 101));
    }

    @Test
    void updateState() {
        manager.registerRevision("grp", "rev-1", RevisionState.LOADING);

        var revisions = manager.getRevisions("grp");
        assertEquals(RevisionState.LOADING, revisions.get(0).state());

        manager.updateState("grp", "rev-1", RevisionState.ACTIVE);

        revisions = manager.getRevisions("grp");
        assertEquals(RevisionState.ACTIVE, revisions.get(0).state());
    }
}
