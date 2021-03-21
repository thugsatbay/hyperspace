/*
 * Copyright (2020) The Hyperspace Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.hyperspace.actions

import org.apache.commons.io.output.ByteArrayOutputStream
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import org.apache.spark.sql.catalyst.plans.logical.LeafNode
import org.apache.spark.sql.functions.{approx_count_distinct, col, input_file_name, udf}
import org.apache.spark.util.sketch.BloomFilter
import com.microsoft.hyperspace.{Hyperspace, HyperspaceException}
import com.microsoft.hyperspace.index._
import com.microsoft.hyperspace.index.DataFrameWriterExtensions.Bucketizer
import com.microsoft.hyperspace.index.sources.FileBasedRelation
import com.microsoft.hyperspace.util.{HyperspaceConf, PathUtils, ResolverUtils}

/**
 * CreateActionBase provides functionality to write dataframe as covering index.
 */
private[actions] abstract class CreateActionBase(dataManager: IndexDataManager) {
  protected lazy val indexDataPath: Path = {
    dataManager
      .getLatestVersionId()
      .map(id => dataManager.getPath(id + 1))
      .getOrElse(dataManager.getPath(0))
  }

  protected val fileIdTracker = new FileIdTracker

  protected def numBucketsForIndex(spark: SparkSession): Int = {
    HyperspaceConf.numBucketsForIndex(spark)
  }

  protected def hasLineage(spark: SparkSession): Boolean = {
    HyperspaceConf.indexLineageEnabled(spark)
  }

  protected def getIndexLogEntry(
      spark: SparkSession,
      df: DataFrame,
      indexConfig: HyperSpaceIndexConfig,
      path: Path): IndexLogEntry = {
    val absolutePath = PathUtils.makeAbsolute(path, spark.sessionState.newHadoopConf())
    val numBuckets = numBucketsForIndex(spark)

    val signatureProvider = LogicalPlanSignatureProvider.create()

    // Resolve the passed column names with existing column names from the dataframe.
    val (indexDataFrame, resolvedIndexedColumns, resolvedIncludedColumns) =
      prepareIndexDataFrame(spark, df, indexConfig)

    signatureProvider.signature(df.queryExecution.optimizedPlan) match {
      case Some(s) =>
        val relation = getRelation(spark, df)
        val sourcePlanProperties = SparkPlan.Properties(
          Seq(relation.createRelationMetadata(fileIdTracker)),
          null,
          null,
          LogicalPlanFingerprint(
            LogicalPlanFingerprint.Properties(Seq(Signature(signatureProvider.name, s)))))

        val indexProperties =
          (hasLineageProperty(spark) ++ hasParquetAsSourceFormatProperty(relation)).toMap

        IndexLogEntry(
          indexConfig.indexName,
          indexConfig match {
            case _: IndexConfig =>
              HyperSpaceIndex.CoveringIndex(
                HyperSpaceIndex.Properties.Covering(
                  HyperSpaceIndex.Properties.CommonProperties
                    .Columns(resolvedIndexedColumns, resolvedIncludedColumns),
                  IndexLogEntry.schemaString(indexDataFrame.schema),
                  numBuckets,
                  indexProperties))
            case _: BloomFilterIndexConfig =>
              HyperSpaceIndex.BloomFilterIndex(
                HyperSpaceIndex.Properties.BloomFilter(
                  HyperSpaceIndex.Properties.CommonProperties
                    .Columns(resolvedIncludedColumns, resolvedIncludedColumns),
                  IndexLogEntry.schemaString(indexDataFrame.schema),
                  indexProperties))
            case _ => throw HyperspaceException("Invalid Index Config.")
          },
          Content.fromDirectory(absolutePath, fileIdTracker),
          Source(SparkPlan(sourcePlanProperties)),
          Map())

      case None => throw HyperspaceException("Invalid plan for creating an index.")
    }
  }

  private def hasParquetAsSourceFormatProperty(
      relation: FileBasedRelation): Option[(String, String)] = {
    if (relation.hasParquetAsSourceFormat) {
      Some(IndexConstants.HAS_PARQUET_AS_SOURCE_FORMAT_PROPERTY -> "true")
    } else {
      None
    }
  }

  private def hasLineageProperty(spark: SparkSession): Option[(String, String)] = {
    if (hasLineage(spark)) {
      Some(IndexConstants.LINEAGE_PROPERTY -> "true")
    } else {
      None
    }
  }

  protected def write(
      spark: SparkSession,
      df: DataFrame,
      indexConfig: HyperSpaceIndexConfig): Unit = {
    indexConfig match {
      case ind: IndexConfig => write(spark, df, ind)
      case ind: BloomFilterIndexConfig => write(spark, df, ind)
      case _ => HyperspaceException("No write op supported")
    }
  }

  private def write(
      spark: SparkSession,
      df: DataFrame,
      indexConfig: BloomFilterIndexConfig): Unit = {
    val (_, resolvedIndexedColumn, _) =
      prepareIndexDataFrame(spark, df, indexConfig)

    require(
      resolvedIndexedColumn.size == 1,
      "Resolved indexed columns for Bloom Filter can only be 1")

    val resolvedNumBits = indexConfig.numBits match {
      case -1 => BloomFilter.create(indexConfig.expectedNumItems).bitSize()
      case _ => indexConfig.numBits
    }

    // TODO Begin has this op as relation is created there
    // TODO Maybe use lineage to make file smaller
    val relations = getRelation(spark, df).createRelationMetadata(fileIdTracker)
    val bloomFilterUDF = udf((path: String) => {
      val bfByteStream = new ByteArrayOutputStream()
      val localBF = spark.read.schema(df.schema)
        .format(relations.fileFormat)
        .options(relations.options)
        .load(path)
        .select(resolvedIndexedColumn.head)
        .stat
        .bloomFilter(resolvedIndexedColumn.head, indexConfig.expectedNumItems, resolvedNumBits)
      localBF.writeTo(bfByteStream)
      bfByteStream.close()
      bfByteStream.toByteArray.map(_.toChar).mkString
    })

    val bloomFilterDF = spark.createDataFrame(
      relations.rootPaths.map(p => Tuple1(p))
    ).toDF("FileName")
    val createBloomFilterData = spark.udf.register("createBloomFilter", bloomFilterUDF)
    val bloomFilterResult = bloomFilterDF.withColumn(
      "Data",
      createBloomFilterData(col("FileName"))
    )
    bloomFilterResult.write.parquet(new Path(indexDataPath, "bf.parquet").toString)
  }

  private def write(spark: SparkSession, df: DataFrame, indexConfig: IndexConfig): Unit = {
    val numBuckets = numBucketsForIndex(spark)

    val (indexDataFrame, resolvedIndexedColumns, _) =
      prepareIndexDataFrame(spark, df, indexConfig)

    // run job
    val repartitionedIndexDataFrame =
      indexDataFrame.repartition(numBuckets, resolvedIndexedColumns.map(df(_)): _*)

    // Save the index with the number of buckets specified.
    repartitionedIndexDataFrame.write
      .saveWithBuckets(
        repartitionedIndexDataFrame,
        indexDataPath.toString,
        numBuckets,
        resolvedIndexedColumns,
        SaveMode.Overwrite)
  }

  protected def getRelation(spark: SparkSession, df: DataFrame): FileBasedRelation = {
    val provider = Hyperspace.getContext(spark).sourceProviderManager
    val relations = df.queryExecution.optimizedPlan.collect {
      case l: LeafNode if provider.isSupportedRelation(l) =>
        provider.getRelation(l)
    }
    // Currently we only support creating an index on a single relation.
    assert(relations.length == 1)
    relations.head
  }

  private def resolveCoveringIndexConfig(
      df: DataFrame,
      indexConfig: IndexConfig): (Seq[String], Seq[String]) = {
    val spark = df.sparkSession
    val dfColumnNames = df.schema.fieldNames
    val indexedColumns = indexConfig.indexedColumns
    val includedColumns = indexConfig.includedColumns
    val resolvedIndexedColumns = ResolverUtils.resolve(spark, indexedColumns, dfColumnNames)
    val resolvedIncludedColumns = ResolverUtils.resolve(spark, includedColumns, dfColumnNames)

    (resolvedIndexedColumns, resolvedIncludedColumns) match {
      case (Some(indexed), Some(included)) => (indexed, included)
      case _ =>
        val unresolvedColumns = (indexedColumns ++ includedColumns)
          .map(c => (c, ResolverUtils.resolve(spark, c, dfColumnNames)))
          .collect { case c if c._2.isEmpty => c._1 }
        throw HyperspaceException(
          s"Columns '${unresolvedColumns.mkString(",")}' could not be resolved " +
            s"from available source columns '${dfColumnNames.mkString(",")}'")
    }
  }

  private def resolveBloomFilterIndexConfig(
      df: DataFrame,
      indexConfig: BloomFilterIndexConfig): String = {
    val spark = df.sparkSession
    val dfColumnNames = df.schema.fieldNames
    val indexedColumn = indexConfig.indexedColumn
    val resolvedIndexedColumns = ResolverUtils.resolve(spark, indexedColumn, dfColumnNames)

    resolvedIndexedColumns match {
      case Some(indexed) => indexed
      case _ =>
        throw HyperspaceException(
          s"Columns '${indexedColumn}' could not be resolved " +
            s"from available source columns '${dfColumnNames.mkString(",")}'")
    }
  }

  private def resolveDataFrameForLineage(
      spark: SparkSession,
      df: DataFrame,
      providedIndexColumns: Seq[String]): DataFrame = {
    if (hasLineage(spark)) {
      val relation = getRelation(spark, df)

      // Lineage is captured using two sets of columns:
      // 1. DATA_FILE_ID_COLUMN column contains source data file id for each index record.
      // 2. If source data is partitioned, all partitioning key(s) are added to index schema
      //    as columns if they are not already part of the schema.
      val partitionColumns = relation.partitionSchema.map(_.name)
      val missingPartitionColumns =
        partitionColumns.filter(ResolverUtils.resolve(spark, _, providedIndexColumns).isEmpty)
      val allIndexColumns = providedIndexColumns ++ missingPartitionColumns

      // File id value in DATA_FILE_ID_COLUMN column (lineage column) is stored as a
      // Long data type value. Each source data file has a unique file id, assigned by
      // Hyperspace. We populate lineage column by joining these file ids with index records.
      // The normalized path of source data file for each record is the join key.
      // We normalize paths by removing extra preceding `/` characters in them,
      // similar to the way they are stored in Content in an IndexLogEntry instance.
      // Path normalization example:
      // - Original raw path (output of input_file_name() udf, before normalization):
      //    + file:///C:/hyperspace/src/test/part-00003.snappy.parquet
      // - Normalized path (used in join):
      //    + file:/C:/hyperspace/src/test/part-00003.snappy.parquet
      import spark.implicits._
      val dataPathColumn = "_data_path"
      val lineagePairs = relation.lineagePairs(fileIdTracker)
      val lineageDF = lineagePairs.toDF(dataPathColumn, IndexConstants.DATA_FILE_NAME_ID)

      df.withColumn(dataPathColumn, input_file_name())
        .join(lineageDF.hint("broadcast"), dataPathColumn)
        .select(
          allIndexColumns.head,
          allIndexColumns.tail :+ IndexConstants.DATA_FILE_NAME_ID: _*)
    } else {
      df.select(providedIndexColumns.head, providedIndexColumns.tail: _*)
    }
  }

  private def prepareIndexDataFrame(
      spark: SparkSession,
      df: DataFrame,
      indexConfig: HyperSpaceIndexConfig): (DataFrame, Seq[String], Seq[String]) = {

    indexConfig match {
      case coveringIndexConfig: IndexConfig =>
        val (resolvedIndexedColumns, resolvedIncludedColumns) =
          resolveCoveringIndexConfig(df, coveringIndexConfig)
        val columnsFromIndexConfig = resolvedIndexedColumns ++ resolvedIncludedColumns
        val indexDF = resolveDataFrameForLineage(spark, df, columnsFromIndexConfig)

        (indexDF, resolvedIndexedColumns, resolvedIncludedColumns)
      case bloomIndexConfig: BloomFilterIndexConfig =>
        val resolvedIndexColumn = Seq(resolveBloomFilterIndexConfig(df, bloomIndexConfig))
        val indexDF = resolveDataFrameForLineage(spark, df, resolvedIndexColumn)

        (indexDF, resolvedIndexColumn, Seq())
    }
  }
}
