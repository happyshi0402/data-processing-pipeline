/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// scalastyle:off println
package org.sevenmob.spark.streaming

import kafka.serializer.StringDecoder

import org.apache.spark.streaming._
import com.datastax.spark.connector._ 
import com.datastax.spark.connector.streaming._
import com.datastax.spark.connector.cql.CassandraConnector
import org.apache.spark.streaming.kafka._
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkContext, SparkConf}
import org.apache.spark.sql.{Row, SQLContext}

//import org.json4s_
//import org.json4s.jackson.JsonMethods._
//import org.json4s.native.JsonParser

import com.eaio.uuid.UUIDGen

import org.sevenmob.geocode._

/*
 * Consumes messages from one or more topics in Kafka process and send them to cassandra.
 * Usage: DirectKafkaProcessing <brokers> <topics>
 *   <brokers> is a list of one or more Kafka brokers
 *   <topics> is a list of one or more kafka topics to consume from
 *   <cassandra-host> is hostname or IP to any of the cassandra nodes
 *   <google-api-key> Google Geoconding API Key
 *
 * Example:
 *    $ bin/run-example org.sevenmob.spark.streaming.DirectKafkaProcessing broker1-host:port,broker2-host:port \
 *    topic1,topic2 cassandra-host apikey123
 */
object DirectKafkaProcessing {

  def main(args: Array[String]) {
    if (args.length < 4) {
      System.err.println(s"""
        |Usage: DirectKafkaProcessing <brokers> <topics> <cassandra-host> <google-api-key>
        |  <brokers> is a list of one or more Kafka brokers
        |  <topics> is a list of one or more kafka topics to consume from
        |  <cassandra-host> is hostname or IP to any of the cassandra nodes
        |  <google-api-key> Google geoconding API Key
        |
        """.stripMargin)
      System.exit(1)
    }

    //implicit val formats = DefaultFormats + UUIDSerialiser

    StreamingExamples.setStreamingLogLevels()

    val Array(brokers, topics, cassandraHost, googleAPIKey) = args

    // Create context with 2 second batch interval
    val conf = new SparkConf().setAppName("DirectKafkaProcessing")
	    .setMaster("local[*]")
    	.set("spark.cassandra.connection.host", cassandraHost)
    	.set("spark.cleaner.ttl", "5000")
    val sc = new SparkContext(conf)
    val ssc = new StreamingContext(sc, Seconds(1))
    val sqlContext = new SQLContext(sc)
    val geoUtil = new GeoLookup()

    // val keySpaceName = "twitter"
    // val tableName = "tweets"

    /* Cassandra setup */
    CassandraConnector(conf).withSessionDo { session =>
	  session.execute("CREATE KEYSPACE IF NOT EXISTS docker_api_calls WITH REPLICATION = {'class': 'SimpleStrategy', 'replication_factor': 1 }")
	  session.execute("CREATE TABLE IF NOT EXISTS docker_api_calls.aggregated_metrics (action_time timeuuid, action text, count int, ip_address text, lat double, lon double, PRIMARY KEY (ip_address, action_time))")
    }
    //INSERT INTO docker_api_calls.aggregated_metrics (action_time, action, count, ip_address)
    //VALUES(now(), 'pull', 1, '123.123.123.123');

    // Create direct kafka stream with brokers and topics and save the results to Cassandra
    val topicsSet = topics.split(",").toSet
    val kafkaParams = Map[String, String]("metadata.broker.list" -> brokers)
    val stream = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder](
      ssc, kafkaParams, topicsSet)
        .map(_._2)

    //val parquetTable = "tweets8"

    stream.foreachRDD { rdd =>
    /* this check is here to handle the empty collection error
       after the 3 items in the static sample data set are processed */
      if (rdd.toLocalIterator.nonEmpty) {
          val temp = sqlContext.jsonRDD(rdd)
          println(temp)
          sqlContext.jsonRDD(rdd).registerTempTable("ApiCall")
          //jsonData.write.parquet("data/" + parquetTable + "/key=" + java.lang.System.currentTimeMillis())
          //val extraFieldsRDD = sc.parallelize(""" {"lat":0.0,"lon":0.0}  """ :: Nil)
          //val extraJsonFields = sqlContext.read.json(extraFieldsRDD)
          //extraJsonFields.write.parquet("data/" + parquetTable + "/key=" + java.lang.System.currentTimeMillis())
          //val parquetData = sqlContext.read.parquet("data/" + parquetTable)
          //parquetData.registerTempTable("Tweets")
          val tweetData = sqlContext.sql("""SELECT * FROM ApiCall""")
          val address = tweetData.map(t => t(0)).collect().head
          val action_time = java.util.UUID.fromString(new com.eaio.uuid.UUID().toString())
          val text = tweetData.map(t => t(1)).collect().head
          val profileUrl = tweetData.map(t => t(2)).collect().head
          val p = geoUtil.fromIP(address.toString).getOrElse((0.0,0.0))
          tweetData.show()
          val collection = sc.parallelize(Seq(ApiCall(text.toString,
                                                      0, address.toString, action_time,
                                                      p._1,p._2)))
          collection.saveToCassandra("docker_api_calls","aggregated_metrics")
      }
    }

//   	.map { case (_, v) =>
//               import org.sevenmob.spark.streaming.UUIDSerialiser
//               implicit val formats = DefaultFormats + UUIDSerialiser
//               JsonParser.parse(v)
//              }
//    val address = for {
//          JObject(child) <- jsonData
//          JField("location", JString(location)) <- child
//        } yield location
//    jsonData.print()
//    address.print()
//    val p = GeocodeObj ? (Parameters(address.toString, ""))
//    val extraJsonFields = parse("{\"lat\":" +  p.lat + ", \"lon\": " + p.lng + "}") 
   	//.saveToCassandra("twitter","tweets")
    //val finalJson = jsonData ~ ("height" -> 175)
//    val finalJson = jsonData merge extraJsonFields   
    //println(extraJsonFields)

    // Start the computation
    ssc.start()
    ssc.awaitTermination()
  }
}

