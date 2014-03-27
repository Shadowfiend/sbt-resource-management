package com.openstudy
package sbt

import java.io.ByteArrayInputStream

import com.amazonaws.auth._
import com.amazonaws.services.s3._
  import model._

trait S3 {
  def accessKey: String
  def secretKey: String

  lazy val credentials = new BasicAWSCredentials(accessKey, secretKey)

  lazy val s3 = new AmazonS3Client(credentials)
}
class S3Handler(val accessKey: String, val secretKey: String, bucket: String) extends S3 {
  lazy val bucketAcl = {
    val acl = new AccessControlList
    acl.grantPermission(GroupGrantee.AllUsers, Permission.Read)

    acl
  }

  // Returns the MD5 checksum of the file.
  def saveFile(mime: String, fileName: String, data: Array[Byte]): String = {
    val metadata = new ObjectMetadata
    metadata.setContentType(mime)

    val dataStream = new ByteArrayInputStream(data)
    val request =
      new PutObjectRequest(bucket, fileName, dataStream, metadata)
        .withAccessControlList(bucketAcl)

    val result = s3.putObject(request)

    result.getContentMd5
  }
}
