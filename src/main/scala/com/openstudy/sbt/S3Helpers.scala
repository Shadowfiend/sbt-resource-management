package com.openstudy
package sbt

import java.io.ByteArrayInputStream

import com.amazonaws.auth._
import com.amazonaws.services.s3._
  import model._

trait S3 {
  def accessKey: Option[String]
  def secretKey: Option[String]

  lazy val credentials = {
    for {
      accessKey <- accessKey
      secretKey <- secretKey
    } yield {
      new BasicAWSCredentials(accessKey, secretKey)
    }
  }

  lazy val s3 = {
    credentials.map { s3Credentials =>
      new AmazonS3Client(s3Credentials)
    } getOrElse {
      new AmazonS3Client
    }
  }
}
class S3Handler(bucket: String, val accessKey: Option[String], val secretKey: Option[String]) extends S3 {
  lazy val bucketAcl = {
    val acl = new AccessControlList
    acl.grantPermission(GroupGrantee.AllUsers, Permission.Read)

    acl
  }

  // Returns the MD5 checksum of the file.
  def saveFile(mime: String, fileName: String, data: Array[Byte]): String = {
    val metadata = new ObjectMetadata
    metadata.setContentType(mime)
    metadata.setContentLength(data.length)

    val dataStream = new ByteArrayInputStream(data)
    val request =
      new PutObjectRequest(bucket, fileName, dataStream, metadata)
        .withAccessControlList(bucketAcl)

    val result = s3.putObject(request)

    result.getContentMd5
  }
}
