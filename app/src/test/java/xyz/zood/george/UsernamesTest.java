package xyz.zood.george;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class UsernamesTest {

    @Test
    public void copiedHandlesWorkInAccountLookup() {
        assertEquals("alexdemo", Usernames.forLookup("  @AlexDemo\n"));
        assertEquals("alexdemo", Usernames.forLookup("alexdemo"));
    }
}
