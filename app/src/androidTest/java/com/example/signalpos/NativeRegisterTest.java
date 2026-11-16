package com.example.signalpos;

import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.containsString;

@RunWith(AndroidJUnit4.class)
public class NativeRegisterTest {
    @Test public void receiptCommitIsDurableAndClearsDraftAtomically() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String server="test://durability", owner="test-owner", key=server+":"+owner;
        String id=java.util.UUID.randomUUID().toString();
        PosStorage storage=new PosStorage(context);
        storage.draft(key,MainActivity.obj("items",new JSONArray().put("draft")));
        JSONObject sale=MainActivity.obj("id",id,"staffId",owner,"total",500);
        storage.saveSale(server,sale,false,key);
        assertEquals(0,storage.draft(key).length());
        storage.close();
        storage=new PosStorage(context);
        assertTrue(storage.receipts(server,owner,true).length()>0);
        assertEquals(0,storage.receipts(server,"other-owner",true).length());
        storage.acknowledge(id); storage.syncError(id,"stale failure");
        JSONArray receipts=storage.receipts(server,owner,false);
        for(int i=0;i<receipts.length();i++) if(id.equals(receipts.getJSONObject(i).getString("id"))) {
            assertFalse(receipts.getJSONObject(i).getBoolean("_pending"));
            assertEquals("",receipts.getJSONObject(i).getString("_error"));
        }
        storage.draft(key,MainActivity.obj("stillHere",true));
        try { storage.saveSale(server,sale,false,key); fail("Duplicate sale must fail"); }
        catch(android.database.sqlite.SQLiteConstraintException expected) { }
        assertTrue(storage.draft(key).getBoolean("stillHere"));
        storage.close();
    }

    @Test public void nativeDemoCheckoutAndRotationKeepOrder() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            onView(withText("Caffè latte")).perform(click());
            onView(withText(startsWith("Add to order"))).perform(click());
            onView(withText(startsWith("Demo charge"))).check(matches(isEnabled()));
            scenario.recreate();
            onView(withText(startsWith("Demo charge"))).check(matches(isEnabled()));
            onView(withText(startsWith("Demo charge"))).perform(click());
            onView(withText("Complete demo sale")).perform(click());
            onView(withText("DEMO RECEIPT · Not a real sale")).check(matches(isDisplayed()));
            onView(withText("Done")).perform(click());
            onView(withText("A fresh start.")).check(matches(isDisplayed()));
            onView(withText("Sales")).perform(click());
            onView(withText("Receipt")).check(matches(isDisplayed()));
            onView(withContentDescription(containsString("View receipt"))).perform(click());
            onView(withText("DEMO RECEIPT · Not a real sale")).check(matches(isDisplayed()));
            onView(withText("Done")).perform(click());
        }
    }
}
