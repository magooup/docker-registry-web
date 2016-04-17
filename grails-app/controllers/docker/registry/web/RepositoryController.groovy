package docker.registry.web

import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Value

class RepositoryController {
  @Value('${registry.readonly}')
  boolean readonly

  def restService

  //{"Type":"registry","Name":"catalog","Action":"*"}
  def index() {
    def repos = restService.get('_catalog', restService.generateAccess('catalog', '*', 'registry')).json.repositories.collect { name ->
      def tagsCount = getTagList(name).size()
      [name: name, tags: tagsCount]
    }
    [repos: repos]
  }

  def tags() {
    def name = params.id.decodeURL()
    def tags = getTags(name)
    if (!tags.count { it.exists })
      redirect action: 'index'
    [tags: tags]
  }


  private def getTags(name) {
    def resp = restService.get("${name}/tags/list").json
    def tags = resp.tags.findAll { it }.collect { tag ->

      def manifest = restService.get("${name}/manifests/${tag}", restService.generateAccess(name))
      def exists = manifest.statusCode.'2xxSuccessful'
      def topLayer
      def size = 0
      def layers
      if (exists) {
        topLayer = new JsonSlurper().parseText(manifest.json.history.first().v1Compatibility)
        layers = getLayers(name, tag)
        size = layers.collect { it.value }.sum()
      }

      // docker uses ISO8601 dates w/ fractional seconds (i.e. yyyy-MM-ddTHH:mm:ss.ssssssssZ),
      // which seem to confuse the Date parser, so truncate the timestamp and always assume UTC tz.
      def createdStr = topLayer?.created?.substring(0,19)
      def createdDate
      if (createdStr) {
        createdDate = Date.parse("yyyy-MM-dd'T'HH:mm:ss", createdStr)
      }

      [name: tag, count: layers?.size(), size: size, exists: exists, id: topLayer?.id?.substring(0, 11), created: createdDate, createdStr: createdStr]
    }
    tags.sort { it.created }.reverse()
  }

  private def getLayers(String name, String tag) {
    def json = restService.get("${name}/manifests/${tag}", true).json

    if (json.schemaVersion == 2)
      return json.layers.collectEntries { [it.digest, it.size] }
    else {
      //fallback to manifest schema v1
      def history = json.history.v1Compatibility.collect { jsonValue ->
        new JsonSlurper().parseText(jsonValue)
      }

      def digests = json.fsLayers.collect { it.blobSum }
      history.eachWithIndex { entry, i ->
        entry.digest = digests[i]
        entry.size = entry.Size ?: 0
      }

      return history.collectEntries {
        [it.digest, it.size]
      }
    }

  }

  private List getTagList(name) {
    restService.get("${name}/tags/list", restService.generateAccess(name)).json?.tags ?: []
  }

  def tag() {
    def name = params.id.decodeURL()
    def tag = params.name
    def res = restService.get("${name}/manifests/${tag}", restService.generateAccess(name)).json
    def history = res.history.v1Compatibility.collect { jsonValue ->
      def json = new JsonSlurper().parseText(jsonValue)
      [id: json.id.substring(0, 11), cmd: json.container_config.Cmd.last().replaceAll('&&', '&&\n')]
    }

    def blobs = res.fsLayers.collect { it.blobSum }
    def layers = getLayers(name, tag)
    history.eachWithIndex { entry, i ->
      def digest = blobs[i]
      entry.size = layers[digest] ?: 0
    }

    [history: history, totalSize: history.sum { it.size }]
  }

  def delete() {
    def name = params.name.decodeURL()
    def tag = params.id
    if (!readonly) {
      def manifest = restService.get("${name}/manifests/${tag}", restService.generateAccess(name, 'pull'), true)
      def digest = manifest.responseEntity.headers.getFirst('Docker-Content-Digest')
      log.info "Manifest digest: $digest"
      /*
    def blobSums = manifest.json.fsLayers?.blobSum
    blobSums.each { digest ->
      log.info "Deleting blob: ${digest}"
      restService.delete("${name}/blobs/${digest}")
    }
    */
      log.info "Deleting manifest"
      restService.delete("${name}/manifests/${digest}", restService.generateAccess(name, '*'))
      //todo: show error/success
    } else
      log.warn 'Readonly mode!'
    redirect action: 'tags', id: params.name
  }
}
