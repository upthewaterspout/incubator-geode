/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.cache.query.internal.index;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionAttributes;
import org.apache.geode.cache.query.IndexStatistics;
import org.apache.geode.cache.query.types.ObjectType;
import org.apache.geode.internal.cache.BucketRegion;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.cache.RegionEntry;

public class MapRangeIndex extends AbstractMapIndex {
  protected final MultiValuedMap entryToMapKeysMap;

  MapRangeIndex(InternalCache cache, String indexName, Region region, String fromClause,
      String indexedExpression, String projectionAttributes, String origFromClause,
      String origIndxExpr, String[] defintions, boolean isAllKeys,
      String[] multiIndexingKeysPattern, Object[] mapKeys, IndexStatistics stats) {
    super(cache, indexName, region, fromClause, indexedExpression, projectionAttributes,
        origFromClause, origIndxExpr, defintions, isAllKeys, multiIndexingKeysPattern, mapKeys,
        stats);
    RegionAttributes ra = region.getAttributes();
    this.entryToMapKeysMap =
        new MultiValuedMap(
            new java.util.concurrent.ConcurrentHashMap(ra.getInitialCapacity(), ra.getLoadFactor(),
                ra.getConcurrencyLevel()),
            true /* user target list as the map keys will be unique */);
  }

  @Override
  void recreateIndexData() throws IMQException {
    /*
     * Asif : Mark the data maps to null & call the initialization code of index
     */
    // TODO:Asif : The statistics data needs to be modified appropriately
    // for the clear operation
    this.mapKeyToValueIndex.clear();
    this.entryToMapKeysMap.clear();
    this.initializeIndex(true);
  }

  @Override
  public boolean containsEntry(RegionEntry entry) {
    // TODO:Asif: take care of null mapped entries
    /*
     * return (this.entryToValuesMap.containsEntry(entry) ||
     * this.nullMappedEntries.containsEntry(entry) || this.undefinedMappedEntries
     * .containsEntry(entry));
     */
    return this.entryToMapKeysMap.containsEntry(entry);
  }

  @Override
  void addMapping(RegionEntry entry) throws IMQException {
    this.evaluator.evaluate(entry, this::saveMapping);
    addSavedMappings(entry);
    clearCurrState();
  }

  public void clearCurrState() {
    for (Object rangeInd : this.mapKeyToValueIndex.values()) {
      ((RangeIndex) rangeInd).clearCurrState();
    }
  }

  private void addSavedMappings(RegionEntry entry) throws IMQException {
    for (Object rangeInd : this.mapKeyToValueIndex.values()) {
      ((RangeIndex) rangeInd).addSavedMappings(entry);
    }
  }

  @Override
  protected void removeMapping(RegionEntry entry, int opCode) throws IMQException {
    // this implementation has a reverse map, so it doesn't handle
    // BEFORE_UPDATE_OP
    if (opCode == BEFORE_UPDATE_OP || opCode == CLEAN_UP_THREAD_LOCALS) {
      return;
    }

    Object values = this.entryToMapKeysMap.remove(entry);
    // Values in reverse coould be null if map in region value does not
    // contain any key which matches to index expression keys.
    if (values == null) {
      return;
    }
    if (values instanceof Collection) {
      Iterator valuesIter = ((Collection) values).iterator();
      while (valuesIter.hasNext()) {
        Object key = valuesIter.next();
        RangeIndex ri = (RangeIndex) this.mapKeyToValueIndex.get(key);
        long start = System.nanoTime();
        this.internalIndexStats.incUpdatesInProgress(1);
        ri.removeMapping(entry, opCode);
        this.internalIndexStats.incUpdatesInProgress(-1);
        long end = -start;
        this.internalIndexStats.incUpdateTime(end);
      }
    } else {
      RangeIndex ri = (RangeIndex) this.mapKeyToValueIndex.get(values);
      long start = System.nanoTime();
      this.internalIndexStats.incUpdatesInProgress(1);
      ri.removeMapping(entry, opCode);
      this.internalIndexStats.incUpdatesInProgress(-1);
      long end = System.nanoTime() - start;
      this.internalIndexStats.incUpdateTime(end);
    }
  }

  @Override
  protected void doIndexAddition(Object mapKey, Object indexKey, Object value, RegionEntry entry)
      throws IMQException {
    boolean isPr = this.region instanceof BucketRegion;
    // Get RangeIndex for it or create it if absent
    RangeIndex rg = (RangeIndex) this.mapKeyToValueIndex.get(mapKey);
    if (rg == null) {
      // use previously created MapRangeIndexStatistics
      IndexStatistics stats = this.internalIndexStats;
      PartitionedIndex prIndex = null;
      if (isPr) {
        prIndex = (PartitionedIndex) this.getPRIndex();
        prIndex.incNumMapKeysStats(mapKey);
      }
      rg = new RangeIndex(cache, indexName + "-" + mapKey, region, fromClause, indexedExpression,
          projectionAttributes, this.originalFromClause, this.originalIndexedExpression,
          this.canonicalizedDefinitions, stats);
      // Shobhit: We need evaluator to verify RegionEntry and IndexEntry inconsistency.
      rg.evaluator = new RangeIndexEvaluatorForMapKey(evaluator, mapKey);
      this.mapKeyToValueIndex.put(mapKey, rg);
      if (!isPr) {
        this.internalIndexStats.incNumMapIndexKeys(1);
      }
    }
    this.internalIndexStats.incUpdatesInProgress(1);
    long start = System.nanoTime();
    rg.addMapping(indexKey, value, entry);
    // This call is skipped when addMapping is called from MapRangeIndex
    // rg.internalIndexStats.incNumUpdates();
    this.internalIndexStats.incUpdatesInProgress(-1);
    long end = System.nanoTime() - start;
    this.internalIndexStats.incUpdateTime(end);
    this.entryToMapKeysMap.add(entry, mapKey);
  }

  @Override
  protected void saveIndexAddition(Object mapKey, Object indexKey, Object value, RegionEntry entry)
      throws IMQException {
    boolean isPr = this.region instanceof BucketRegion;
    // Get RangeIndex for it or create it if absent
    RangeIndex rg = (RangeIndex) this.mapKeyToValueIndex.get(mapKey);
    if (rg == null) {
      // use previously created MapRangeIndexStatistics
      IndexStatistics stats = this.internalIndexStats;
      PartitionedIndex prIndex = null;
      if (isPr) {
        prIndex = (PartitionedIndex) this.getPRIndex();
        prIndex.incNumMapKeysStats(mapKey);
      }
      rg = new RangeIndex(cache, indexName + "-" + mapKey, region, fromClause, indexedExpression,
          projectionAttributes, this.originalFromClause, this.originalIndexedExpression,
          this.canonicalizedDefinitions, stats);
      rg.evaluator = new RangeIndexEvaluatorForMapKey(evaluator, mapKey);
      this.mapKeyToValueIndex.put(mapKey, rg);
      if (!isPr) {
        this.internalIndexStats.incNumMapIndexKeys(1);
      }
    }
    // rg.internalIndexStats.incUpdatesInProgress(1);
    long start = System.nanoTime();
    rg.saveMapping(indexKey, value, entry);
    // This call is skipped when addMapping is called from MapRangeIndex
    // rg.internalIndexStats.incNumUpdates();
    this.internalIndexStats.incUpdatesInProgress(-1);
    long end = System.nanoTime() - start;
    this.internalIndexStats.incUpdateTime(end);
    this.entryToMapKeysMap.add(entry, mapKey);
  }

  public static class RangeIndexEvaluatorForMapKey implements RangeIndexedExpressionEvaluator {

    private final Object mapKey;
    private final RangeIndexedExpressionEvaluator delegate;

    RangeIndexEvaluatorForMapKey(RangeIndexedExpressionEvaluator delegate,
        Object mapKey) {
      this.delegate = delegate;
      this.mapKey = mapKey;
    }

    @Override
    public void evaluate(RegionEntry target,
        RangeIndexEvaluator.IndexUpdateOperation updateOperation)
        throws IMQException {
      delegate.evaluate(target, getIndexUpdateOperation(updateOperation));
    }

    @Override
    public void initializeIndex(boolean loadEntries, IndexUpdateOperation updateOperation)
        throws IMQException {
      delegate.initializeIndex(loadEntries, getIndexUpdateOperation(updateOperation));
    }

    @Override
    public boolean isFirstItrOnKey() {
      return delegate.isFirstItrOnKey();
    }

    @Override
    public boolean isFirstItrOnEntry() {
      return delegate.isFirstItrOnEntry();
    }


    @Override
    public String getIndexedExpression() {
      return delegate.getIndexedExpression();
    }

    @Override
    public String getFromClause() {
      return delegate.getFromClause();
    }

    @Override
    public String getProjectionAttributes() {
      return delegate.getProjectionAttributes();
    }

    @Override
    public ObjectType getIndexResultSetType() {
      return delegate.getIndexResultSetType();
    }

    @Override
    public void expansion(List expandedResults, Object lowerBoundKey, Object upperBoundKey,
        int lowerBoundOperator, int upperBoundOperator, Object value)
        throws IMQException {
      delegate.expansion(expandedResults, lowerBoundKey, upperBoundKey, lowerBoundOperator,
          upperBoundOperator, value);
    }

    private IndexUpdateOperation getIndexUpdateOperation(
        IndexUpdateOperation updateOperation) {
      return (indexKey, value, regionEntry) -> updateOperation.add(extractKey(indexKey), value,
          regionEntry);
    }

    private Object extractKey(Object indexKey) {
      if (!(indexKey instanceof Map)) {
        return null;
      }
      return ((Map) indexKey).get(mapKey);
    }

  }
}
