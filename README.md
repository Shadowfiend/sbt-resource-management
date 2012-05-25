# SBT Resource Management

SBT Resource Management is a plugin designed to handle:

 - Compiling CoffeeScript to JavaScript.
 - Compiling SASS to CSS via compass.
 - Combining and compressing JavaScript files via YUI Compressor.
 - Combining and compressing CSS stylesheets via YUI Compressor.
 - Deploying combined/compressed JS and CSS to Amazon S3.

Commands are provided for each of these steps, and some convention-based
configuration is used to bundle JS and CSS into bundles that can be used
in Lift. Notably, there is a component of the bundling process that
requires support in the Lift app, not in sbt. More on that in a moment.

## Building

You can clone this repository:

    $ git clone git://github.com/Shadowfiend/sbt-resource-management.git

And then run publish-local to publish it to your local Ivy repository:

    $ cd sbt-resource-management
    $ sbt publish-local

## Adding as a plugin

In your project's project/build.sbt:

    addSbtPlugin("com.openstudy" % "sbt-resource-management" % "0.1.1-SNAPSHOT")

## Setup

Deploying to S3 requires three settings to be specified in your build.sbt:

    awsAccessKey := "YOUR_ACCESS_KEY"
  
    awsSecretKey := "YOUR_SECRET_KEY"

    awsS3Bucket  := "YOUR_S3_BUCKET

For example, at OpenStudy, our S3 bucket could be devsets.openstudy.com
for development mode assets.

You can then import the settings from this plugin:

    seq(resourceManagementSettings :_*)

## Defaults

By default, the following directories/files are used; listed beside them are the
variables that can be used to customize these:

 - `src/main/webapp/*/javascripts` for JavaScript (`scriptDirectories in ResourceCompile`)
 - `src/main/webapp/*/stylesheets` for CSS (`styleDirectories in ResourceCompile`)
 - `src/main/webapp/**/*.coffee` for CoffeeScript (`coffeeScriptSources in ResourceCompile`)
 - `target/compiled-coffee-script` for compiled CoffeeScript (`compiledCoffeeScriptDirectory in ResourceCompile`)
 - `target/javascripts` for all JavaScript + compiled CoffeeScript (`targetJavaScriptDirectory in ResourceCompile`)
 - `target/compressed` for compressed JS and CSS (`compressedTarget in ResourceCompile`) (javascripts and
    stylesheets directories are created under this one for each type of file).

## Commands

The main command for JavaScript is `sbt resources:copy-scripts`. This does two things:

 - Compile any CoffeeScript (`resources:compile-coffee-script`) to the compiled CoffeeScript directory.
 - Copy any regular JavaScript files and any compiled CoffeeScript files to the target JavaScript directory, from where they can be served jointly.

For SASS files, you can run `sbt resources:compile-sass`. Notably, this
runs the `compass` command, so compass must be installed and you must
have a config.rb file in your project root. It also sets the
asset_domain environment variable to the value of awsS3Bucket, which is
used by compass to create proper asset paths, and sets the compass
environment to production. You can use this in your config.rb to act
differently in the face of production settings (we use this to enable
relative_assets only when the environment is NOT production and to
toggle the output style from between compressed and expanded).

## Bundles and compression

SBT resource management offers a relatively simple way to bundle
JavaScript files together, use them normally in development, and then
combine them in production. By default, it looks in the
`src/main/resources/bundles` directory
(`bundleDirectory in ResourceCompile`) for two files:
`javascript.bundle` (`scriptBundle in ResourceCompile`) and
`stylesheet.bundle` (`styleBundle in ResourceCompile`).

These files take the following form:

    first-bundle-name
    first-bundle-file-1
    first-bundle-file-2
    first-bundle-file-3
    ...

    second-bundle-name
    second-bundle-file-1
    ...

The bundle names have no extension, while the files inside them are
expected to have their proper file extension. For example, we have this
in OpenStudy's javascript.bundle:

    landing
    openstudy-base.js
    openstudy-analytics.js
    landing.js
    shim-and-shiv.js
    login-and-signup.js
    placeholders.js
    templates.js
    error-handling.js

The resulting bundle will be called landing.js in production, and will
contain the contents of the files listed below it.

CSS is similar:

    landing
    museo.css
    login.css
    landing.css

The resulting bundle here is landing.css.

Scripts and CSS are combined and compressed with the two commands:

    $ sbt resources:compress-scripts
    $ sbt resources:compress-css

These read the above bundle definitions and create the appropriate
combined, compressed bundle files, dropping them in the compressed
target directory from above. Note that `compress-scripts` first runs
`copy-scripts`, and so will compile CoffeeScript if needed.

There is a combination command, `resources:compress-resources`, that
runs both the compression commands.

When sbt-resource-management compresses your resources, it also drops
2 files in the resources directory alongside the javascript.bundle and
stylesheet.bundle files. These files describe the "versions" of the
resulting bundles. By default, they are in the bundles directory at
`javascript-bundle-versions` (`scriptBundleVersions in ResourceCompile`)
and `stylesheet-bundle-versions`
(`styleBundleVersions in ResourceCompile`). An example of a
javascript-bundle-versions file from OpenStudy:

    base-libs=eb973464460455b7b3369d2423cbce28
    feed=52f1ad2c66be23b554dfc46a987040e7
    group=1f8a721879bbfdd24f23e455a6b67bf5
    hi-im-ie8-and-i-dont-have-a-proper-ecma-implementation-but-ten-percent-of-our-users-still-use-me=ec4fa5b263f0aa8c2a1b3d0cc1bd40eb
    jquery-address=1d3f939fe75ebe1e8975f1728c0925ee
    landing=477cfa71fab9c9c36833b4af0b362688
    loading=cda37f6b044678b545f227cece6055fb
    profile=2a9097d76cb16b8875bd5b15f14eddb3
    search-landing=c6fe1030bb5a2fff52d90744627e19d8
    settings=f2854751746eae5b427ff929c6652544
    single-pane=10a20a17c6b0862baca031cff63c2765

The versions here are MD5 hashes, and can be used to append a
cache-buster querystring to your script and link tags.

You can also "mash" the scripts, meaning create the joined bundle files
without YUI compression, by running `sbt resources:mash-scripts`.

## Deployment to S3

If you defined the AWS settings above, SBT resource management can also
push your compressed script and CSS bundles to S3. Just run:

    $ sbt resources:deploy-scripts
    $ sbt resources:deploy-css

This will create javascripts/ and stylesheets/ directories in the
specified S3 bucket with the specified bundles.

Once again, there is a combination command,
`resources:deploy-resources`, that runs both the deploy commands.

## Bundles: the Lift side

The Lift side of bundles is provided by a simple snippet, called Bundles
and set up at Boot as two snippets: `script-bundle` and
`style-bundle`. The source for the relevant snippet is at
https://gist.github.com/2790765 . Somewhere in your Boot process, you
can add:

    LiftRules.snippets.append(Bundles.snippetHandlers)

This will install the snippets. You can then refer to any of the bundles
in your bundle files by name:

    <tail>
      <lift:style-bundle name="feed" />

      <lift:script-bundle name="feed" />
      <lift:script-bundle name="tools" />
      <lift:script-bundle name="group" />
      <lift:script-bundle name="loading" />
    </tail>

Notably, the bundles snippet above will include the expanded, unbundled
files in development mode, and only try to include the bundled files in
production mode. When the bundled files are included, the version files
from the above bundling step are used to append the proper cache-buster
querystring to the script and link tags. When the non-bundled files are
included, Lift's internal attachResourceId is used to attach the
cache-busting querystring. attacheResourceId by default attaches a
different unique id every time the server is run, but uses the same one
within the same server run. You can change that by replacing
LiftRules.attachResourceId.

# License

sbt-resource-management is provided under the terms of the MIT
License. See the LICENSE file in this same directory.

# Author/Contributors

sbt-resource-management is copyright me, Antonio Salazar Cardozo, and
licensed uner the terms of the MIT License. No warranties are made,
express or implied. See the LICENSE file in this same directory for more
details.

I have a rather sporadically updated blog at http://shadowfiend.posterous.com/.

I am the Director of Engineering at [OpenStudy](http://openstudy.com),
where we're working on changing the face of education as we know it
using Scala, Lift, and a variety of other cool tools.

Also contains contributions from Matt Farmer,
@[farmdawgnation](http://github.com/farmdawgnation), one of the stupidly
smart developers at OpenStudy.
