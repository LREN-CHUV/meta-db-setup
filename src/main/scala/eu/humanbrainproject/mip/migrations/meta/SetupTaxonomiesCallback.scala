/*
 * Copyright 2017 LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.humanbrainproject.mip.migrations.meta

import java.sql.Connection

import org.flywaydb.core.api.callback.{ Callback, Context, Event }
import doobie._
import doobie.free.KleisliInterpreter
import cats.instances.all._
import cats.effect.{ ContextShift, IO }
import io.circe.Json
import io.circe.parser.parse
import org.everit.json.schema.ValidationException
import org.json.JSONObject

import scala.io.Source

case class TaxonomyDefinition(source: String,
                              hierarchy: Json,
                              targetTable: String,
                              histogramGroupings: List[String]) {

  def taxonomy: Json = hierarchy

}

class SetupTaxonomiesCallback extends Callback with ValidateTaxonomySchema {

  override def supports(event: Event, context: Context): Boolean = event == Event.AFTER_MIGRATE

  override def canHandleInTransaction(
      event: Event,
      context: Context
  ): Boolean = true

  override def handle(event: Event, context: Context): Unit = event match {
    case Event.AFTER_MIGRATE => setupTaxonomies(context.getConnection)
    case _                   => ()

  }

  @SuppressWarnings(
    Array("org.wartremover.warts.Any",
          "org.wartremover.warts.NonUnitStatements",
          "org.wartremover.warts.Throw")
  )
  def setupTaxonomies(connection: Connection): Unit = {

    implicit val ListMeta: Meta[List[String]] =
      Meta[String].timap(_.split(",").toList)(_.mkString(","))

    implicit val cs: ContextShift[IO] =
      IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)
    val blockingExecutionContextR = ExecutionContexts.cachedThreadPool[IO]

    blockingExecutionContextR
      .use { blockingExecutionContext =>
        // Creating an KleisliInterpreter for some Catchable: Suspendable
        val kleisliInt = KleisliInterpreter[IO](blockingExecutionContext)
        // Using the default ConnectionInterpreter:
        val nat = kleisliInt.ConnectionInterpreter

        def deleteTaxonomies(ps: List[String]): ConnectionIO[Int] = {
          val sql = "DELETE FROM meta_variables WHERE source = ?"
          Update[String](sql).updateMany(ps)
        }

        def insertTaxonomies(ps: List[TaxonomyDefinition]): ConnectionIO[Int] = {
          val sql =
            "INSERT into meta_variables (source, hierarchy, target_table, histogram_groupings) VALUES (?, ?, ?, ?)"
          Update[TaxonomyDefinition](sql).updateMany(ps)
        }

        for (taxonomy <- taxonomies) {
          validateTaxonomy(new JSONObject(taxonomy.taxonomy))
            .recoverWith {
              case e: ValidationException =>
                println(s"Invalid hierarchy: ${e.getMessage}")
                println(taxonomy.taxonomy)
                throw e
            }
        }

        val regenerateMeta = for {
          rm    <- deleteTaxonomies(taxonomies.map(_.source))
          added <- insertTaxonomies(taxonomies)
        } yield (rm, added)

        regenerateMeta.foldMap(nat).run(connection)
      }
      .unsafeRunSync()
    ()

  }

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  lazy val taxonomies: List[TaxonomyDefinition] = {
    val env = Option(System.getenv("TAXONOMIES")).getOrElse("")
    env
      .split(" ")
      .filter(_.nonEmpty)
      .map { rawDef =>
        val t = rawDef.split("\\|")
        if (t.length < 2) {
          throw new IllegalArgumentException(
            s"Invalid format for TAXONOMIES environment variable. Found ${t.toList.mkString("|")}, expecting source|target_table|histogram_groupings"
          )
        }
        val source             = t.head
        val taxonomy           = Source.fromFile(s"/src/variables/$source.json").mkString
        val taxonomyJson       = parse(taxonomy).left.map[Json](e => throw e).merge
        val targetTable        = t(1)
        val histogramGroupings = if (t.length == 2) Nil else t(2).split(",").toList

        TaxonomyDefinition(source, taxonomyJson, targetTable, histogramGroupings)
      }
      .toList
  }

}
