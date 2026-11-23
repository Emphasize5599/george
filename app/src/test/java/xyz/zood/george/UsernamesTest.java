package xyz.zood.george;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class UsernamesTest {

    @Test
    public void identifiesOwnHandleAfterNormalization() {
        org.junit.Assert.assertTrue(Usernames.isOwn("  @FamilyDemo ", "familydemo"));
        org.junit.Assert.assertFalse(Usernames.isOwn("@anotheruser", "familydemo"));
        org.junit.Assert.assertFalse(Usernames.isOwn("familydemo", null));
    }

    @Test
    public void copiedHandlesWorkInAccountLookup() {
        assertEquals("alexdemo", Usernames.forLookup("  @AlexDemo\n"));
        assertEquals("alexdemo", Usernames.forLookup("alexdemo"));
    }
}
