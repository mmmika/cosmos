package com.mesosphere.cosmos

import com.twitter.util.Try

/** A package cache that stores all package information in memory. Useful for testing.
  *
  * @param packages the contents of the cache: Marathon JSON files indexed by package name
  */
final case class MemoryPackageCache(packages: Map[String, String]) extends PackageCache {

  def get(packageName: String): Try[Option[String]] = Try(packages.get(packageName))

}
