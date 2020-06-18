/*
 * Copyright 2000-2020 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.flow.data.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.Range;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;
import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import elemental.json.JsonValue;

public class DataCommunicatorTest {
    /**
     * Test item that uses id for identity.
     */
    private static class Item {
        private final int id;
        private String value;

        public Item(int id) {
            this(id, "Item " + id);
        }

        public Item(int id, String value) {
            this.id = id;
            this.value = value;
        }

        @Override
        public String toString() {
            return id + ": " + value;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Item) {
                Item that = (Item) obj;
                return that.id == id;
            } else {
                return false;
            }
        }

        @Override
        public int hashCode() {
            return id;
        }
    }

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    private DataCommunicator<Item> dataCommunicator;

    @Mock
    private DataGenerator<Item> dataGenerator;
    @Mock
    private ArrayUpdater arrayUpdater;

    private Element element;
    private MockUI ui;

    private ArrayUpdater.Update update;

    public Range lastClear = null;
    public Range lastSet = null;
    public int lastUpdateId = -1;

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
        ui = new MockUI();
        element = new Element("div");
        ui.getElement().appendChild(element);

        lastClear = null;
        lastSet = null;
        lastUpdateId = -1;

        update = new ArrayUpdater.Update() {

            @Override
            public void clear(int start, int length) {
                lastClear = Range.withLength(start, length);
            }

            @Override
            public void set(int start, List<JsonValue> items) {
                lastSet = Range.withLength(start, items.size());
            }

            @Override
            public void commit(int updateId) {
                lastUpdateId = updateId;
            }
        };

        Mockito.when(arrayUpdater.startUpdate(Mockito.anyInt()))
                .thenReturn(update);

        dataCommunicator = new DataCommunicator<>(dataGenerator, arrayUpdater,
                data -> {
                }, element.getNode());
        dataCommunicator.setPageSize(50);
    }

    @Test
    public void communicator_with_0_items_should_not_refresh_all() {
        dataCommunicator.setRequestedRange(0, 0);
        fakeClientCommunication();

        Assert.assertEquals(Range.withLength(0, 0), lastSet);
        Assert.assertNull(
                "Only requestAll should clear items. This may make us loop.",
                lastClear);

        dataCommunicator.setRequestedRange(0, 0);
        fakeClientCommunication();

        Assert.assertEquals(Range.withLength(0, 0), lastSet);
        Assert.assertNull(
                "Only requestAll should clear items. Which would make us loop.",
                lastClear);
    }

    @Test
    public void communicator_with_items_should_send_updates_but_not_refresh_all() {
        dataCommunicator.setDataProvider(createDataProvider(), null);

        dataCommunicator.setRequestedRange(0, 50);
        fakeClientCommunication();

        Assert.assertEquals(
                "Expected request range for 50 items on first request.",
                Range.withLength(0, 50), lastSet);

        dataCommunicator.setRequestedRange(0, 70);
        fakeClientCommunication();

        Assert.assertEquals("Expected request range for 20 new items.",
                Range.withLength(50, 20), lastSet);
    }

    @Test
    public void reattach_different_roundtrip_refresh_all() {
        dataCommunicator.setDataProvider(createDataProvider(), null);
        dataCommunicator.setRequestedRange(0, 50);
        fakeClientCommunication();

        Assert.assertEquals("Expected initial full reset.",
                Range.withLength(0, 50), lastSet);
        lastSet = null;

        element.removeFromParent();
        fakeClientCommunication();

        Assert.assertNull("Expected no during reattach.", lastSet);

        ui.getElement().appendChild(element);
        fakeClientCommunication();

        Assert.assertEquals("Expected initial full reset after reattach",
                Range.withLength(0, 50), lastSet);
    }

    @Test
    public void reattach_same_roundtrip_refresh_nothing() {
        dataCommunicator.setDataProvider(createDataProvider(), null);
        dataCommunicator.setRequestedRange(0, 50);
        fakeClientCommunication();

        Assert.assertEquals("Expected initial full reset.",
                Range.withLength(0, 50), lastSet);
        lastSet = null;

        element.removeFromParent();

        Assert.assertNull("Expected no communication during reattach", lastSet);

        ui.getElement().appendChild(element);
        fakeClientCommunication();

        Assert.assertNull("Expected no communication after reattach", lastSet);
    }

    @Test
    public void setDataProvider_keyMapperIsReset() {
        dataCommunicator.setDataProvider(createDataProvider(), null);
        dataCommunicator.setRequestedRange(0, 50);
        fakeClientCommunication();

        Assert.assertEquals(0, dataCommunicator.getKeyMapper().get("1").id);

        dataCommunicator.setDataProvider(createDataProvider(), null);
        Assert.assertNull(
                "The KeyMapper should be reset when a new DataProvider is set",
                dataCommunicator.getKeyMapper().get("1"));
    }

    @Test
    public void dataProviderBreaksContract_limitIsNotCalled_throw() {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            items.add(new Item(i));
        }
        DataProvider<Item, Void> dataProvider = DataProvider
                .fromCallbacks(query -> {
                    return items.stream();
                }, query -> {
                    return items.size();
                });
        dataCommunicator.setDataProvider(dataProvider, null);

        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage(CoreMatchers.containsString(
                "The data provider hasn't ever called getLimit"));
        dataCommunicator.fetchFromProvider(0, 1);
    }

    @Test
    public void dataProviderBreaksContract_offsetIsNotCalled_throw() {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            items.add(new Item(i));
        }
        DataProvider<Item, Void> dataProvider = DataProvider
                .fromCallbacks(query -> {
                    query.getLimit();
                    return items.stream();
                }, query -> {
                    return items.size();
                });
        dataCommunicator.setDataProvider(dataProvider, null);

        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage(CoreMatchers.containsString(
                "The data provider hasn't ever called getOffset"));
        dataCommunicator.fetchFromProvider(1, 1);
    }

    @Test
    public void dataProviderBreaksContract_tooManyItems_throw() {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            items.add(new Item(i));
        }
        DataProvider<Item, Void> dataProvider = DataProvider
                .fromCallbacks(query -> {
                    query.getOffset();
                    query.getLimit();
                    return items.stream();
                }, query -> {
                    return items.size();
                });
        dataCommunicator.setDataProvider(dataProvider, null);

        Stream<Item> stream = dataCommunicator.fetchFromProvider(0, 1);

        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage(CoreMatchers.containsString(
                "exceeds the limit specified by the query (1)."));

        stream.forEach(item -> {
        });
    }

    @Test
    public void sameKeyDifferentInstance_latestInstanceUsed() {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            items.add(new Item(i));
        }
        // Abusing the fact that ListDataProvider doesn't copy the backing store
        ListDataProvider<Item> dataProvider = new ListDataProvider<>(items);
        dataCommunicator.setDataProvider(dataProvider, null);

        dataCommunicator.setRequestedRange(0, 50);
        fakeClientCommunication();

        Item originalItem = items.get(0);
        String key = dataCommunicator.getKeyMapper().key(originalItem);

        Assert.assertSame(originalItem,
                dataCommunicator.getKeyMapper().get(key));

        Item updatedItem = new Item(originalItem.id, "Updated");
        items.set(0, updatedItem);
        dataProvider.refreshAll();

        fakeClientCommunication();

        Assert.assertSame(updatedItem,
                dataCommunicator.getKeyMapper().get(key));
    }

    @Test
    public void dataProviderReturnsLessItemsThanRequested_aNewSizeQueryIsPerformed() {
        AbstractDataProvider<Item, Object> dataProvider = createDataProviderThatChangesSize(
                50, 10);
        dataProvider = Mockito.spy(dataProvider);
        dataCommunicator.setDataProvider(dataProvider, null);

        // The first request will return size 50, but the actual fetch will
        // bring only 40 items. A new size query should then be performed, that
        // will return 40 instead
        dataCommunicator.setRequestedRange(0, 50);
        fakeClientCommunication();

        Assert.assertEquals(40, lastSet.getEnd());
        // Assert takes into acount the inital size query for setting
        // dataprovider.
        Mockito.verify(dataProvider, Mockito.times(2)).size(Mockito.any());
        Mockito.verify(dataProvider, Mockito.times(1)).fetch(Mockito.any());
    }

    @Test
    public void setSizeCallback_usedForDataSize() {
        AbstractDataProvider<Item, Object> dataProvider = createDataProvider();
        dataProvider = Mockito.spy(dataProvider);

        dataCommunicator.setDataProvider(dataProvider, null);
        dataCommunicator.setRequestedRange(0, 50);
        Assert.assertTrue(dataCommunicator.isDefinedSize());

        fakeClientCommunication();

        AtomicBoolean sizeCallbackCall = new AtomicBoolean(false);
        dataCommunicator.setSizeCallback(query -> {
            sizeCallbackCall.set(true);
            return 100;
        });
        Assert.assertTrue(dataCommunicator.isDefinedSize());

        fakeClientCommunication();

        Assert.assertTrue("SizeCallback not called",
                sizeCallbackCall.getAndSet(false));
        Assert.assertEquals("Size not used", 100,
                dataCommunicator.getDataSize());

        Mockito.verify(dataProvider, Mockito.times(1)).size(Mockito.any());
        Mockito.verify(dataProvider, Mockito.times(1)).fetch(Mockito.any());

        dataCommunicator.setRequestedRange(50, 50);

        fakeClientCommunication();

        Mockito.verify(dataProvider, Mockito.times(1)).size(Mockito.any());
        Mockito.verify(dataProvider, Mockito.times(2)).fetch(Mockito.any());

        Assert.assertFalse("SizeCallback called when should not have",
                sizeCallbackCall.get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void setSizeCallback_null_throws() {
        dataCommunicator.setDataProvider(createDataProvider(), null);
        dataCommunicator.setSizeCallback(null);
    }

    @Test
    public void setSizeEstimateCallback_usedForSizeEstimate() {
        final int dataSize = 175;
        AbstractDataProvider<Item, Object> dataProvider = createDataProvider(
                dataSize);
        dataProvider = Mockito.spy(dataProvider);

        AtomicInteger previousSizeEstimate = new AtomicInteger(0);
        AtomicInteger estimate = new AtomicInteger(120);
        AtomicInteger requestedRangeEnd = new AtomicInteger(50);

        dataCommunicator.setDataProvider(dataProvider, null);
        dataCommunicator.setRequestedRange(0, requestedRangeEnd.get());
        Assert.assertTrue(dataCommunicator.isDefinedSize());

        dataCommunicator.setSizeEstimateCallback(query -> {
            Assert.assertEquals("invalid previous size estimate",
                    previousSizeEstimate.get(),
                    query.getPreviousSizeEstimate());
            Assert.assertEquals("invalid requested range end",
                    requestedRangeEnd.get(), query.getRequestedRangeEnd());
            return estimate.get();
        });
        Assert.assertFalse(dataCommunicator.isDefinedSize());

        fakeClientCommunication();

        Assert.assertEquals("estimated size not used", estimate.get(),
                dataCommunicator.getDataSize());
        Mockito.verify(dataProvider, Mockito.times(0)).size(Mockito.any());
        Mockito.verify(dataProvider, Mockito.times(1)).fetch(Mockito.any());

        // scroll next to last page -> trigger another estimate
        dataCommunicator.setRequestedRange(50, 50);
        previousSizeEstimate.set(estimate.getAndSet(200));
        requestedRangeEnd.set(100);

        fakeClientCommunication();

        Assert.assertEquals("estimated size not used", estimate.get(),
                dataCommunicator.getDataSize());
        Mockito.verify(dataProvider, Mockito.times(0)).size(Mockito.any());
        Mockito.verify(dataProvider, Mockito.times(2)).fetch(Mockito.any());

        // scroll until items end before reaching estimate -> size is adjusted
        dataCommunicator.setRequestedRange(150, 50);
        previousSizeEstimate.set(estimate.getAndSet(300));
        requestedRangeEnd.set(200);

        fakeClientCommunication();

        Assert.assertEquals("size not adjusted after ran out of items",
                dataSize, dataCommunicator.getDataSize());
        Mockito.verify(dataProvider, Mockito.times(0)).size(Mockito.any());
        Mockito.verify(dataProvider, Mockito.times(3)).fetch(Mockito.any());
    }

    @Test
    public void setSizeEstimateCallback_AfterReachingEndNotTriggeredUntilReset() {
        final int dataSize = 75;
        AbstractDataProvider<Item, Object> dataProvider = createDataProvider(
                dataSize);
        dataProvider = Mockito.spy(dataProvider);
        dataCommunicator.setDataProvider(dataProvider, null);

        SizeEstimateCallback<Item, Object> sizeEstimateCallback = new SizeEstimateCallback<Item, Object>() {
            @Override
            public int sizeEstimate(SizeEstimateQuery<Item, Object> query) {
                return 200;
            }
        };
        sizeEstimateCallback = Mockito.spy(sizeEstimateCallback);
        dataCommunicator.setSizeEstimateCallback(sizeEstimateCallback);

        dataCommunicator.setRequestedRange(0, 50);
        fakeClientCommunication();

        Mockito.verify(dataProvider, Mockito.times(0)).size(Mockito.any());
        Mockito.verify(dataProvider, Mockito.times(1)).fetch(Mockito.any());
        Mockito.verify(sizeEstimateCallback, Mockito.times(1))
                .sizeEstimate(Mockito.any());

        dataCommunicator.setRequestedRange(50, 50);
        fakeClientCommunication();

        Mockito.verify(dataProvider, Mockito.times(0)).size(Mockito.any());
        Mockito.verify(dataProvider, Mockito.times(2)).fetch(Mockito.any());
        Mockito.verify(sizeEstimateCallback, Mockito.times(1))
                .sizeEstimate(Mockito.any());

        dataCommunicator.setRequestedRange(0, 50);
        fakeClientCommunication();
        dataCommunicator.setRequestedRange(50, 25);
        fakeClientCommunication();

        Mockito.verify(dataProvider, Mockito.times(0)).size(Mockito.any());
        Mockito.verify(dataProvider, Mockito.times(4)).fetch(Mockito.any());
        Mockito.verify(sizeEstimateCallback, Mockito.times(1))
                .sizeEstimate(Mockito.any());

        dataCommunicator.reset();
        fakeClientCommunication();

        Mockito.verify(dataProvider, Mockito.times(0)).size(Mockito.any());
        Mockito.verify(dataProvider, Mockito.times(5)).fetch(Mockito.any());
        Mockito.verify(sizeEstimateCallback, Mockito.times(2))
                .sizeEstimate(Mockito.any());
    }

    @Test
    public void setSizeEstimateCallback_invalidEstimateGiven_throws() {
        dataCommunicator.setDataProvider(createDataProvider(), null);
        dataCommunicator.setDefinedSize(false);
        dataCommunicator.setRequestedRange(0, 50);
        AtomicBoolean expectReset = new AtomicBoolean(true);
        dataCommunicator.setSizeEstimateCallback(query -> {
            Assert.assertEquals("Reset state in query doesn't match",
                    expectReset.get(), query.isReset());
            // this is fine after a reset, but later on
            return query.getRequestedRangeEnd() - 1;
        });

        fakeClientCommunication();

        // trigger another request
        dataCommunicator.setRequestedRange(100, 50);
        expectReset.set(false);
        try {
            fakeClientCommunication();
        } catch (IllegalStateException ise) {
            // success
            return;
        }
        Assert.fail(
                "Returning an estimated size less than the range end should fail when the data is not reset");
    }

    @Test(expected = IllegalArgumentException.class)
    public void setSizeEstimateCallback_null_throws() {
        dataCommunicator.setDataProvider(createDataProvider(), null);
        dataCommunicator.setSizeEstimateCallback(null);
    }

    @Test
    public void setInitialSizeEstimate_usedInitiallyThenDiscarded() {
        AbstractDataProvider<Item, Object> dataProvider = createDataProvider(
                250);
        dataProvider = Mockito.spy(dataProvider);
        dataCommunicator.setDataProvider(dataProvider, null);

        final int initialSizeEstimate = 100;
        dataCommunicator.setInitialSizeEstimate(initialSizeEstimate);
        dataCommunicator.setRequestedRange(0, 50);
        Assert.assertFalse(dataCommunicator.isDefinedSize());

        fakeClientCommunication();

        Assert.assertEquals("initial size estimate not used",
                initialSizeEstimate, dataCommunicator.getDataSize());
        Mockito.verify(dataProvider, Mockito.times(0)).size(Mockito.any());
        Mockito.verify(dataProvider, Mockito.times(1)).fetch(Mockito.any());

        dataCommunicator.setRequestedRange(50, 50);

        fakeClientCommunication();

        Assert.assertEquals("initial size estimate was not discarded",
                initialSizeEstimate + getPageSizeIncrease(),
                dataCommunicator.getDataSize());
        Mockito.verify(dataProvider, Mockito.times(0)).size(Mockito.any());
        Mockito.verify(dataProvider, Mockito.times(2)).fetch(Mockito.any());
    }

    @Test
    public void setInitialSizeEstimate_lessThanCurrentFetchedSize_discarded() {
        AbstractDataProvider<Item, Object> dataProvider = createDataProvider(
                250);

        dataCommunicator.setDataProvider(dataProvider, null);
        dataCommunicator.setDefinedSize(false);
        dataCommunicator.setRequestedRange(0, 50);
        fakeClientCommunication();

        final int initialSizeEstimate = 111;
        dataCommunicator.setInitialSizeEstimate(initialSizeEstimate);
        Assert.assertFalse(dataCommunicator.isDefinedSize());

        dataCommunicator.setRequestedRange(50, 100);
        fakeClientCommunication();

        Assert.assertEquals(
                "too small initial size estimate should not be applied",
                getPageSizeIncrease(), dataCommunicator.getDataSize());
    }

    @Test
    public void setInitialSizeEstimate_lessThanRequestedRange_sizeIsIncreasedAutomatically() {
        AbstractDataProvider<Item, Object> dataProvider = createDataProvider(
                250);
        dataProvider = Mockito.spy(dataProvider);
        dataCommunicator.setDataProvider(dataProvider, null);
        int requestedRangeEnd = 50;
        dataCommunicator.setRequestedRange(0, requestedRangeEnd);

        final int initialSizeEstimate = 49;
        dataCommunicator.setInitialSizeEstimate(initialSizeEstimate);

        fakeClientCommunication();
        Assert.assertEquals(
                "Size should be automatically adjusted for too small estimate",
                requestedRangeEnd + dataCommunicator.getPageSize(),
                dataCommunicator.getDataSize());
    }

    @Test(expected = IllegalArgumentException.class)
    public void setInitialSizeEstimate_lessThanOne_throws() {
        dataCommunicator.setInitialSizeEstimate(0);
    }

    @Test
    public void getActiveItemOnIndex_activeRangeChanges_itemsReturned() {
        dataCommunicator.setDataProvider(createDataProvider(300), null);
        dataCommunicator.setRequestedRange(0, 50);
        fakeClientCommunication();
        Assert.assertEquals("Wrong active item", new Item(0),
                dataCommunicator.getActiveItemOnIndex(0));
        Assert.assertEquals("Wrong active item", new Item(49),
                dataCommunicator.getActiveItemOnIndex(49));


        dataCommunicator.setRequestedRange(50, 50);
        fakeClientCommunication();

        Assert.assertEquals("Wrong active item", new Item(50),
                dataCommunicator.getActiveItemOnIndex(50));
        Assert.assertEquals("Wrong active item", new Item(69),
                dataCommunicator.getActiveItemOnIndex(69));
        Assert.assertEquals("Wrong active item", new Item(99),
                dataCommunicator.getActiveItemOnIndex(99));
    }

    @Test
    public void isItemActive_newItems() {
        dataCommunicator.setDataProvider(createDataProvider(), null);
        dataCommunicator.setRequestedRange(0, 50);

        Assert.assertFalse("Item should not be active", dataCommunicator.isItemActive(new Item(0)));

        fakeClientCommunication();

        Assert.assertTrue("Item should be active", dataCommunicator.isItemActive(new Item(0)));
        Assert.assertTrue("Item should be active", dataCommunicator.isItemActive(new Item(49)));
        Assert.assertFalse("Item should not be active", dataCommunicator.isItemActive(new Item(50)));

        dataCommunicator.setRequestedRange(50, 50);
        fakeClientCommunication();

        Assert.assertTrue("Item should be active", dataCommunicator.isItemActive(new Item(50)));
        Assert.assertTrue("Item should be active", dataCommunicator.isItemActive(new Item(99)));
        Assert.assertFalse("Item should not be active", dataCommunicator.isItemActive(new Item(100)));
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void getActiveItemOnIndex_outsizeActiveRange_throws() {
        dataCommunicator.setDataProvider(createDataProvider(300), null);
        dataCommunicator.setRequestedRange(50, 50);
        fakeClientCommunication();

        Assert.assertEquals("Wrong active item", new Item(50),
                dataCommunicator.getActiveItemOnIndex(50));
        dataCommunicator.getActiveItemOnIndex(49);
    }

    private int getPageSizeIncrease() {
        return dataCommunicator.getPageSize()
                * dataCommunicator.getSizeIncreasePageCount();
    }

    private void fakeClientCommunication() {
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        ui.getInternals().getStateTree().collectChanges(ignore -> {
        });
    }

    private AbstractDataProvider<Item, Object> createDataProviderThatChangesSize(
            final int size, final int delta) {
        return new AbstractDataProvider<Item, Object>() {
            private boolean modifiedCount;

            @Override
            public boolean isInMemory() {
                return true;
            }

            @Override
            public int size(Query<Item, Object> query) {
                if (modifiedCount) {
                    return size - delta;
                }
                return size;
            }

            @Override
            public Stream<Item> fetch(Query<Item, Object> query) {
                int count = query.getLimit() + query.getOffset();
                if (!modifiedCount) {
                    count -= delta;
                    modifiedCount = true;
                }
                return IntStream.range(query.getOffset(), count)
                        .mapToObj(Item::new);
            }
        };
    }

    private AbstractDataProvider<Item, Object> createDataProvider() {
        return new AbstractDataProvider<Item, Object>() {
            @Override
            public boolean isInMemory() {
                return true;
            }

            @Override
            public int size(Query<Item, Object> query) {
                return 100;
            }

            @Override
            public Stream<Item> fetch(Query<Item, Object> query) {
                return IntStream
                        .range(query.getOffset(),
                                query.getLimit() + query.getOffset())
                        .mapToObj(Item::new);
            }
        };
    }

    private AbstractDataProvider<Item, Object> createDataProvider(int size) {
        return new AbstractDataProvider<Item, Object>() {
            @Override
            public boolean isInMemory() {
                return true;
            }

            @Override
            public int size(Query<Item, Object> query) {
                return size;
            }

            @Override
            public Stream<Item> fetch(Query<Item, Object> query) {
                int end = Math.min(query.getRequestedRangeEnd(), size);
                return IntStream.range(query.getOffset(), end)
                        .mapToObj(Item::new);
            }
        };
    }

    public static class MockUI extends UI {

        public MockUI() {
            this(findOrcreateSession());
        }

        public MockUI(VaadinSession session) {
            getInternals().setSession(session);
            setCurrent(this);
        }

        @Override
        protected void init(VaadinRequest request) {
            // Do nothing
        }

        private static VaadinSession findOrcreateSession() {
            VaadinSession session = VaadinSession.getCurrent();
            if (session == null) {
                session = new AlwaysLockedVaadinSession(null);
                VaadinSession.setCurrent(session);
            }
            return session;
        }
    }

    public static class AlwaysLockedVaadinSession extends MockVaadinSession {

        public AlwaysLockedVaadinSession(VaadinService service) {
            super(service);
            lock();
        }

    }

    public static class MockVaadinSession extends VaadinSession {
        /*
         * Used to make sure there's at least one reference to the mock session
         * while it's locked. This is used to prevent the session from being
         * eaten by GC in tests where @Before creates a session and sets it as
         * the current instance without keeping any direct reference to it. This
         * pattern has a chance of leaking memory if the session is not unlocked
         * in the right way, but it should be acceptable for testing use.
         */
        private static final ThreadLocal<MockVaadinSession> referenceKeeper = new ThreadLocal<>();

        public MockVaadinSession(VaadinService service) {
            super(service);
        }

        @Override
        public void close() {
            super.close();
            closeCount++;
        }

        public int getCloseCount() {
            return closeCount;
        }

        @Override
        public Lock getLockInstance() {
            return lock;
        }

        @Override
        public void lock() {
            super.lock();
            referenceKeeper.set(this);
        }

        @Override
        public void unlock() {
            super.unlock();
            referenceKeeper.remove();
        }

        private int closeCount;

        private ReentrantLock lock = new ReentrantLock();
    }

}
