/*
* Copyright (c) 2014 Snowplow Analytics Ltd. All rights reserved.
*
* This program is licensed to you under the Apache License Version 2.0, and
* you may not use this file except in compliance with the Apache License
* Version 2.0.  You may obtain a copy of the Apache License Version 2.0 at
* http://www.apache.org/licenses/LICENSE-2.0.
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the Apache License Version 2.0 is distributed on an "AS
* IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
* implied.  See the Apache License Version 2.0 for the specific language
* governing permissions and limitations there under.
*/
package com.snowplowanalytics.iglu.repositories.scalaserver

// This project
import core.SchemaActor
import core.SchemaActor._

// Akka
import akka.actor.{ Actor, Props, ActorSystem }
import akka.pattern.ask
import akka.util.Timeout

// Scala
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Await

// Spray
import spray.http._
import spray.routing._
import MediaTypes._
import StatusCodes._

class RepoServiceActor extends Actor with RepoService {
  implicit def actorRefFactory = context

  def receive = runRoute(route)
}

trait RepoService extends HttpService {
  val apiKeyStore = DynamoFactory.apiKeyStore

  val schemaActor = actorRefFactory.actorOf(Props[SchemaActor])
  implicit val timeout = Timeout(5.seconds)

  val authenticator = TokenAuthenticator[String]("api-key") {
    key => util.FutureConverter.fromTwitter(apiKeyStore.get(key))
  }
  def auth: Directive1[String] = authenticate(authenticator)

  val route = rejectEmptyResponse {
    pathPrefix("[a-z.]+".r / "[a-zA-Z0-9_-]+".r / "[a-z]+".r /
    "[0-9]+-[0-9]+-[0-9]+".r) { (vendor, name, format, version) => {
      val key = s"${vendor}/${name}/${format}/${version}"
      auth { permission =>
        pathEnd {
          get {
            respondWithMediaType(`application/json`) {
              complete {
                Await.result(schemaActor ? Get(key), 5.seconds).
                asInstanceOf[Option[String]] match {
                  case Some(str) => str
                  case None => NotFound
                }
              }
            }
          }
        } ~
        anyParam('json)(json =>
          post {
            respondWithMediaType(`text/html`) {
              complete {
                if (permission == "write") {
                  Await.result(schemaActor ? Get(key), 5.seconds).
                  asInstanceOf[Option[String]] match {
                    //return unauthorized for now
                    case Some(str) => Unauthorized
                    case None => {
                      schemaActor ! Put((key, Some(json)))
                      "Success"
                    }
                  }
                } else {
                  Unauthorized
                }
              }
            }
          })
      }
    }}
  }
}
