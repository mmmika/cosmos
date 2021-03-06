package com.mesosphere.cosmos.rpc.v1.model

import com.mesosphere.cosmos.error.CosmosError
import io.circe.Decoder
import io.circe.Encoder
import io.circe.JsonObject
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder

case class ErrorResponse(
  `type`: String,
  message: String,
  data: Option[JsonObject] = None
)

object ErrorResponse {
  implicit val encode: Encoder[ErrorResponse] = deriveEncoder
  implicit val decode: Decoder[ErrorResponse] = deriveDecoder

  def apply(error: CosmosError) : ErrorResponse = {
    ErrorResponse(
      error.getClass.getSimpleName,
      error.message,
      error.data
    )
  }
}
