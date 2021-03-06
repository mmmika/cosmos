package com.mesosphere.cosmos

import _root_.io.circe.Decoder
import _root_.io.circe.Json
import com.mesosphere.cosmos.circe.Decoders.decode
import com.mesosphere.cosmos.error.GenericHttpError
import com.mesosphere.cosmos.error.UnsupportedContentType
import com.mesosphere.cosmos.http.MediaTypes
import com.mesosphere.cosmos.http.RequestSession
import com.mesosphere.error.ResultOps
import com.mesosphere.http.MediaType
import com.netaporter.uri.Uri
import com.twitter.finagle.http.Fields
import com.twitter.finagle.http.Request
import com.twitter.finagle.http.RequestBuilder
import com.twitter.finagle.http.Response
import com.twitter.finagle.http.Status
import com.twitter.io.Buf
import com.twitter.util.Future
import io.netty.handler.codec.http.HttpResponseStatus
import org.jboss.netty.handler.codec.http.HttpMethod
import scala.reflect.ClassTag

abstract class ServiceClient(baseUri: Uri) {

  private[this] val cleanedBaseUri: String = Uris.stripTrailingSlash(baseUri)
  private[this] val cosmosVersion: String = BuildProperties().cosmosVersion

  protected def get(uri: Uri)(implicit session: RequestSession): Request = {
    baseRequestBuilder(uri)
      .setHeader(Fields.Accept, MediaTypes.applicationJson.show)
      .buildGet
  }

  protected def post(uri: Uri, jsonBody: Json)(implicit session: RequestSession): Request = {
    baseRequestBuilder(uri)
      .setHeader(Fields.Accept, MediaTypes.applicationJson.show)
      .setHeader(Fields.ContentType, MediaTypes.applicationJson.show)
      .buildPost(Buf.Utf8(jsonBody.noSpaces))
  }

  protected def put(uri: Uri, jsonBody: Json)(implicit session: RequestSession): Request = {
    baseRequestBuilder(uri)
      .setHeader(Fields.Accept, MediaTypes.applicationJson.show)
      .setHeader(Fields.ContentType, MediaTypes.applicationJson.show)
      .buildPut(Buf.Utf8(jsonBody.noSpaces))
  }

  protected def postForm(uri: Uri, postBody: String)(implicit session: RequestSession): Request = {
    baseRequestBuilder(uri)
      .setHeader(Fields.Accept, MediaTypes.applicationJson.show)
      .setHeader(Fields.ContentType, "application/x-www-form-urlencoded; charset=utf-8")
      .buildPost(Buf.Utf8(postBody))
  }

  protected def delete(uri: Uri)(implicit session: RequestSession): Request = {
    baseRequestBuilder(uri)
      .setHeader(Fields.Accept, MediaTypes.applicationJson.show)
      .buildDelete()
  }

  protected def validateResponseStatus(method: HttpMethod, uri: Uri, response: Response): Future[Response] = {
    response.status match {
      case Status.Ok =>
        Future.value(response)
      case s: Status =>
        throw GenericHttpError(method, uri, HttpResponseStatus.valueOf(s.code)).exception
    }
  }

  protected def decodeJsonTo[A: ClassTag](response: Response)(implicit d: Decoder[A]): A = {
    response.headerMap.get(Fields.ContentType) match {
      case Some(ct) =>
        MediaType.parse(ct).map { mediaType =>
          // Marathon and Mesos don't specify 'charset=utf-8' on it's json, so we are lax in our comparison here.
          MediaType.compatibleIgnoringParameters(
            MediaTypes.applicationJson,
            mediaType
          ) match {
            case false =>
              throw UnsupportedContentType.forMediaType(
                List(MediaTypes.applicationJson),
                Some(mediaType)
              ).exception
            case true =>
              decode[A](response.contentString).getOrThrow
          }
        }.get
      case _ =>
        throw UnsupportedContentType(List(MediaTypes.applicationJson)).exception
    }
  }

  protected def decodeTo[A: Decoder: ClassTag](
    method: HttpMethod,
    uri: Uri,
    response: Response
  ): Future[A] = {
    validateResponseStatus(method, uri, response)
      .map(decodeJsonTo[A])
  }

  private[cosmos] final def baseRequestBuilder(
    uri: Uri
  )(
    implicit session: RequestSession
  ): RequestBuilder[RequestBuilder.Valid, Nothing] = {
    val builder = RequestBuilder()
      .url(s"$cleanedBaseUri${uri.toString}")
      .setHeader(Fields.UserAgent, s"cosmos/$cosmosVersion")

    session.authorization match {
      case Some(auth) => builder.setHeader("Authorization", auth.headerValue)
      case _ => builder
    }
  }
}
