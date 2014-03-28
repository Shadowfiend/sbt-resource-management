package com.openstudy
package sbt

import java.io._
import java.util.zip.GZIPOutputStream

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
  def saveFile(mime: String, fileName: String, data: Array[Byte], gzipped: Boolean): String = {
    val finalData =
      if (gzipped) {
        val dataOutputStream = new ByteArrayOutputStream
        val gzipOutputStream = new GZIPOutputStream(dataOutputStream)

        gzipOutputStream.write(data, 0, data.length)
        gzipOutputStream.close

        dataOutputStream.toByteArray
      } else {
        data
      }

    val metadata = new ObjectMetadata
    metadata.setContentType(mime)
    metadata.setContentLength(finalData.length)
    if (gzipped) {
      metadata.setContentEncoding("gzip")
    }

    val dataStream = new ByteArrayInputStream(finalData)
    val request =
      new PutObjectRequest(bucket, fileName, dataStream, metadata)
        .withAccessControlList(bucketAcl)

    val result = s3.putObject(request)

    result.getContentMd5
  }
}
