package com.openstudy
package sbt

import java.io._

import org.jets3t.service._
  import acl._
  import impl.rest.httpclient._
  import model._
  import security._
  import utils._

trait S3 {
  def accessKey : String
  def secretKey : String

  lazy val credentials =
    new AWSCredentials(accessKey, secretKey)
  lazy val s3 = new RestS3Service(credentials)
}
class S3Handler(val accessKey:String, val secretKey:String, bucket:String) extends S3 {
  lazy val bucketAcl = {
    val acl = s3.getBucketAcl(bucket);
    acl.grantPermission(GroupGrantee.ALL_USERS, Permission.PERMISSION_READ);

    acl
  }

  // Returns the MD5 checksum of the file.
  def saveFile(mime:String, fileName:String, data:Array[Byte]) : String = {
    val s3Object = new S3Object(fileName, data)
    val hash = s3Object.getMd5HashAsHex

    s3Object.setContentType(mime)
    s3Object.setAcl(bucketAcl)

    s3.putObject(bucket, s3Object)

    hash
  }
}
