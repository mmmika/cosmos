package com.mesosphere.cosmos.handler

import java.io.{StringReader, StringWriter}
import java.util.Base64

import cats.data.Xor
import com.github.mustachejava.DefaultMustacheFactory
import com.mesosphere.cosmos.http.MediaTypes
import com.mesosphere.cosmos.jsonschema.JsonSchemaValidation
import com.mesosphere.cosmos.model._
import com.mesosphere.cosmos.{JsonSchemaMismatch, PackageCache, PackageFileNotJson, PackageRunner}
import com.mesosphere.cosmos.model.mesos.master.MarathonApp
import com.twitter.io.Charsets
import com.twitter.util.Future
import io.circe.parse.parse
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}
import io.finch.DecodeRequest

import scala.collection.JavaConverters._

private[cosmos] final class PackageInstallHandler(packageCache: PackageCache, packageRunner: PackageRunner)
  (implicit bodyDecoder: DecodeRequest[InstallRequest], encoder: Encoder[InstallResponse])
  extends EndpointHandler[InstallRequest, InstallResponse] {

  val accepts = MediaTypes.InstallRequest
  val produces = MediaTypes.InstallResponse

  import PackageInstallHandler._

  override def apply(request: InstallRequest): Future[InstallResponse] = {
    packageCache
      .getPackageByPackageVersion(request.packageName, request.packageVersion)
      .flatMap { packageFiles =>
        val packageConfig = preparePackageConfig(request, packageFiles)
        packageRunner
          .launch(packageConfig)
          .map { runnerResponse =>
            val packageName = packageFiles.packageJson.name
            val packageVersion = packageFiles.packageJson.version
            val appId = runnerResponse.id
            InstallResponse(packageName, packageVersion, appId)
          }
      }
  }

}

private[cosmos] object PackageInstallHandler {

  import com.mesosphere.cosmos.circe.Encoders._  //TODO: Not crazy about this being here
  private[this] val MustacheFactory = new DefaultMustacheFactory()

  private def preparePackageConfig(
    request: InstallRequest,
    packageFiles: PackageFiles
  ): Json = {
    val marathonJson = renderMustacheTemplate(packageFiles, request.options)
    val marathonJsonWithLabels = addLabels(marathonJson, packageFiles)

    request.appId match {
      case Some(id) => marathonJsonWithLabels.mapObject(_ + ("id", id.asJson))
      case _ => marathonJsonWithLabels
    }
  }

  private[this] def renderMustacheTemplate(
    packageFiles: PackageFiles,
    options: Option[JsonObject]
  ): Json = {
    val strReader = new StringReader(packageFiles.marathonJsonMustache)
    val mustache = MustacheFactory.compile(strReader, "marathon.json.mustache")

    val defaults = extractDefaultsFromConfig(packageFiles.configJson)
    val merged: JsonObject = (packageFiles.configJson, options) match {
      case (None, None) => JsonObject.empty
      case (Some(config), None) => defaults   // TODO: Potential future improvement, validate the defaults against the schema
      case (None, Some(_)) => throw JsonSchemaMismatch()
      case (Some(config), Some(opts)) =>
        val m = merge(defaults, opts)
        if (!JsonSchemaValidation.matchesSchema(m, config)) {
          throw JsonSchemaMismatch()
        }
        m
    }

    val resource = extractAssetsAsJson(packageFiles.resourceJson)
    val complete = merged + ("resource", Json.fromJsonObject(resource))
    val params = jsonToJava(Json.fromJsonObject(complete))

    val output = new StringWriter()
    mustache.execute(output, params)
    parse(output.toString) match {
      case Xor.Left(err) => throw PackageFileNotJson("marathon.json", err.message)
      case Xor.Right(rendered) => rendered
    }
  }

  private[this] def extractAssetsAsJson(resource: Option[Resource]): JsonObject = {
    val assets = resource.map(_.assets) match {
      case Some(a) => a.asJson
      case _ => Json.obj()
    }

    JsonObject.singleton("assets", assets)
  }

  private[this] def extractDefaultsFromConfig(configJson: Option[JsonObject]): JsonObject = {
    configJson
      .flatMap { json =>
        val topProperties =
          json("properties")
            .getOrElse(Json.empty)

        filterDefaults(topProperties)
          .asObject
      }
      .getOrElse(JsonObject.empty)
  }

  private[this] def filterDefaults(properties: Json): Json = {
    val defaults = properties
      .asObject
      .getOrElse(JsonObject.empty)
      .toMap
      .flatMap { case (propertyName, propertyJson) =>
        propertyJson
          .asObject
          .flatMap { propertyObject =>
            propertyObject("default").orElse {
              propertyObject("properties").map(filterDefaults)
            }
          }
          .map(propertyName -> _)
      }

    Json.fromJsonObject(JsonObject.fromMap(defaults))
  }

  private[this] def jsonToJava(json: Json): Any = {
    json.fold(
      jsonNull = null,
      jsonBoolean = identity,
      jsonNumber = n => n.toInt.getOrElse(n.toDouble),
      jsonString = identity,
      jsonArray = _.map(jsonToJava).asJava,
      jsonObject = _.toMap.mapValues(jsonToJava).asJava
    )
  }

  private[this] def addLabels(
    marathonJson: Json,
    packageFiles: PackageFiles
  ): Json = {

    val packageMetadataJson = getPackageMetadataJson(packageFiles)

    val packageMetadata = encodeForLabel(packageMetadataJson)

    val commandMetadata = packageFiles.commandJson.map { commandJson =>
      val bytes = commandJson.asJson.noSpaces.getBytes(Charsets.Utf8)
      Base64.getEncoder.encodeToString(bytes)
    }

    val isFramework = packageFiles.packageJson.framework.getOrElse(true)

    val frameworkName = packageFiles.configJson.flatMap { json =>
      Json.fromJsonObject(json).cursor
        .downField(packageFiles.packageJson.name)
        .flatMap(_.get[String]("framework-name").toOption)
    }

    val requiredLabels: Map[String, String] = Map(
      (MarathonApp.metadataLabel, packageMetadata),
      (MarathonApp.registryVersionLabel, packageFiles.packageJson.packagingVersion),
      (MarathonApp.nameLabel, packageFiles.packageJson.name),
      (MarathonApp.versionLabel, packageFiles.packageJson.version),
      (MarathonApp.sourceLabel, packageFiles.sourceUri.toString),
      (MarathonApp.releaseLabel, packageFiles.revision),
      (MarathonApp.isFrameworkLabel, isFramework.toString)
    )

    val optionalLabels: Map[String, String] = Seq(
      frameworkName.map("PACKAGE_FRAMEWORK_NAME_KEY" -> _),
      commandMetadata.map(MarathonApp.commandLabel -> _)
    ).flatten.toMap

    val existingLabels = marathonJson.cursor
      .get[Map[String, String]]("labels").getOrElse(Map.empty)

    val packageLabels = existingLabels ++ requiredLabels ++ optionalLabels

    marathonJson.mapObject(_ + ("labels", packageLabels.asJson))
  }

  private[this] def getPackageMetadataJson(packageFiles: PackageFiles): Json = {
    val packageJson = packageFiles.packageJson.asJson

    // add images to package.json metadata for backwards compatability in the UI
    val imagesJson = packageFiles.resourceJson.map(_.images.asJson)
    val packageWithImages = imagesJson match {
      case Some(images) =>
        packageJson.mapObject(_ + ("images", images))
      case None =>
        packageJson
    }

    removeNulls(packageWithImages)
  }

  /** Circe populates omitted fields with null values; remove them (see GitHub issue #56) */
  private[this] def removeNulls(json: Json): Json = {
    json.mapObject { obj =>
      JsonObject.fromMap(obj.toMap.filterNot { case (k, v) => v.isNull })
    }
  }

  private[this] def encodeForLabel(json: Json): String = {
    val bytes = json.noSpaces.getBytes(Charsets.Utf8)
    Base64.getEncoder.encodeToString(bytes)
  }

  private[cosmos] def merge(target: JsonObject, fragment: JsonObject): JsonObject = {
    fragment.toList.foldLeft(target) { (updatedTarget, fragmentEntry) =>
      val (fragmentKey, fragmentValue) = fragmentEntry
      val targetValueOpt = updatedTarget(fragmentKey)

      val mergedValue = (targetValueOpt.flatMap(_.asObject), fragmentValue.asObject) match {
        case (Some(targetObject), Some(fragmentObject)) =>
          Json.fromJsonObject(merge(targetObject, fragmentObject))
        case _ => fragmentValue
      }

      updatedTarget + (fragmentKey, mergedValue)
    }
  }

}