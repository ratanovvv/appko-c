import org.csanchez.jenkins.plugins.kubernetes.pipeline.PodTemplateAction

// Defining variables

// Fix for kubernetes-plugin issue 
def clearTemplateNames() {
    currentBuild.rawBuild.getAction(PodTemplateAction.class)?.stack?.clear()
}

// Project and Google Credentials
gcpProjectName = "ratanovvv"
imageRepository = "gcr.io/${gcpProjectName}"
source_credentials = "source:${gcpProjectName}"
registry_credentials = "gcr:${gcpProjectName}"

// Dockerfiles
builderDockerfile = '''FROM gcc:8
RUN apt-get update && apt-get install -y --no-install-recommends build-essential autoconf automake libtool groff perl && rm -rf /var/lib/apt/lists/*
'''
utilsDockerfile = '''FROM alpine
RUN apk update && apk --no-cache add wget tar gzip && rm -rf /var/cache/apk/*
'''

dockerImageTag = "0.0.1"
builderImage = [ dockerfile: builderDockerfile, name: "${imageRepository}/gcc-builder:${dockerImageTag}" ]
utilsImage = [ dockerfile: utilsDockerfile, name: "${imageRepository}/utils:${dockerImageTag}" ]

customDockerImages = [
  // builder: builderImage,
  // utils: utilsImage
]

// Pods definition for build stage
pods = [:]

pods['curl'] = {
    clearTemplateNames()
    podTemplate(label: 'curl', cloud: 'cluster-1', alwaysPullImage: true, containers: [
        containerTemplate(name: "gcc-curl-${env.BUILD_NUMBER}", image: "${builderImage.name}", command: 'cat', ttyEnabled: true),
        containerTemplate(name: "nginx-${env.BUILD_NUMBER}", image: "nginx")
        ]) {
        node('curl') {
            container("gcc-curl-${env.BUILD_NUMBER}"){
                dir('gcc-curl'){deleteDir()}
                dir('gcc-curl'){
                    unstash 'curl-files'
                    sh './buildconf && ./configure && make && make prefix=/usr install && ls -alth && curl --version'
                }
            }
        }
    }
}

pods['pg'] = {
    clearTemplateNames()
    podTemplate(label: 'pg', cloud: 'cluster-1', alwaysPullImage: true, containers: [
        containerTemplate(name: "gcc-pg-${env.BUILD_NUMBER}",   
            image: "${builderImage.name}",  
            command: 'cat', 
            ttyEnabled: true,   
            envVars: [
                    envVar(key: "POSTGRES_PASSWORD", value: "gccsample"),
                    envVar(key: "POSTGRES_USER", value: "gccsample"),
                    envVar(key: "POSTGRES_DB", value: "gccsample")
            ]),
        containerTemplate(name: "pg-${env.BUILD_NUMBER}",
            image: "postgres:9.6",
            envVars: [
                    envVar(key: "POSTGRES_PASSWORD", value: "gccsample"),
                    envVar(key: "POSTGRES_USER", value: "gccsample"),
                    envVar(key: "POSTGRES_DB", value: "gccsample")
            ])
        ]) {
        node('pg') {
            container("gcc-pg-${env.BUILD_NUMBER}"){
                dir('gcc-pg'){deleteDir()}
                dir('gcc-pg'){
                    unstash 'psql-files'
                    sh '''
                    sed -i "/fmgr/d" src/include/Makefile
                    ./configure CFLAGS="-O2 -pipe" --without-zlib 
                    make -C src/bin install
                    make -C src/include install
                    make -C src/interfaces install
                    make -C doc install
                    echo "127.0.0.1:5432:\$POSTGRES_DB:\$POSTGRES_USER:\$POSTGRES_PASSWORD" > .pgpass && chmod 600 .pgpass
                    /usr/local/pgsql/bin/psql -h 127.0.0.1 -U \$POSTGRES_USER \$POSTGRES_DB -c "SELECT * FROM pg_catalog.pg_tables;"
                    '''
                }
            }
        }
    }
}

pods['nginx'] = {
    clearTemplateNames()
    podTemplate(label: 'nginx', cloud: 'cluster-1', alwaysPullImage: true, containers: [
        containerTemplate(name: "gcc-nginx-${env.BUILD_NUMBER}", image: "${builderImage.name}", command: 'cat', ttyEnabled: true)
        ]) {
        node('nginx') {
            container("gcc-nginx-${env.BUILD_NUMBER}"){
                dir('gcc-nginx'){deleteDir()}
                dir('gcc-nginx'){
                    unstash 'nginx-files'
                    sh '''
                    ./configure \
                     --without-select_module \
                     --without-poll_module \
                     --without-http_charset_module \
                     --without-http_gzip_module \
                     --without-http_ssi_module \
                     --without-http_userid_module \
                     --without-http_access_module \
                     --without-http_auth_basic_module \
                     --without-http_mirror_module \
                     --without-http_autoindex_module \
                     --without-http_geo_module \
                     --without-http_map_module \
                     --without-http_split_clients_module \
                     --without-http_referer_module \
                     --without-http_rewrite_module \
                     --without-http_proxy_module \
                     --without-http_fastcgi_module \
                     --without-http_uwsgi_module \
                     --without-http_scgi_module \
                     --without-http_grpc_module \
                     --without-http_memcached_module \
                     --without-http_limit_conn_module \
                     --without-http_limit_req_module \
                     --without-http_empty_gif_module \
                     --without-http_browser_module \
                     --without-http_upstream_hash_module \
                     --without-http_upstream_ip_hash_module \
                     --without-http_upstream_least_conn_module \
                     --without-http_upstream_keepalive_module \
                     --without-http_upstream_zone_module \
                     --without-http-cache \
                     --without-mail_pop3_module \
                     --without-mail_imap_module \
                     --without-mail_smtp_module \
                     --without-stream_limit_conn_module \
                     --without-stream_access_module \
                     --without-stream_geo_module \
                     --without-stream_map_module \
                     --without-stream_split_clients_module \
                     --without-stream_return_module \
                     --without-stream_upstream_hash_module \
                     --without-stream_upstream_least_conn_module \
                     --without-stream_upstream_zone_module \
                     --without-pcre
                     make && make install
                    '''
                    sh 'ls -alth'
                    sh '/usr/local/nginx/sbin/nginx -V'
                }
            }
        }
    }
}


// Start of pipeline
stage("rebuild custom docker images") {
    if (customDockerImages.size() > 0) {
        podTemplate(label: 'pod', cloud: 'cluster-1', alwaysPullImage: true, containers: [
            containerTemplate(name: "docker", image: "docker", command: "cat", ttyEnabled: true)],
            volumes: [hostPathVolume(hostPath: "/var/run/docker.sock", mountPath: "/var/run/docker.sock")]) {
            node('pod') {
                
                stages = [:]

                customDockerImages.each { key, value ->
                    stages[key] = {
                        container("docker") {
                            docker.withRegistry("https://${imageRepository}", "${registry_credentials}") {

                                dir("${key}-${env.BUILD_NUMBER}") {
                                    writeFile file: "Dockerfile", text: value.dockerfile
                                    sh "cat Dockerfile"
                                    sh "docker build -t ${value.name} ."
                                    def app = docker.image("${value.name}")
                                    app.push()
                                }
                            }
                        }
                    }
                }

                parallel stages
                
            }
        }
    }
}
clearTemplateNames()

podTemplate(label: 'pod', cloud: 'cluster-1', alwaysPullImage: true, containers: [
    containerTemplate(name: "git", image: "alpine/git", command: "cat", ttyEnabled: true),
    containerTemplate(name: "utils", image: "${utilsImage.name}",command: "cat", ttyEnabled: true)
    ]) {
    node('pod') {
        stage('checkout'){ parallel (

            nginx: { container("utils"){
                dir("wget-nginx-${env.BUILD_NUMBER}"){deleteDir()}
                dir("wget-nginx-${env.BUILD_NUMBER}"){
                    sh 'wget http://nginx.org/download/nginx-1.15.2.tar.gz --no-check-certificate'
                    sh 'tar xzf nginx-1.15.2.tar.gz'
                    sh 'ls -alth'
                    dir("nginx-1.15.2"){
                        sh 'ls -alth'
                        stash "nginx-files"
                    }
                }
            }},

            pg: { container("utils"){
                dir("wget-pg-${env.BUILD_NUMBER}"){deleteDir()}
                dir("wget-pg-${env.BUILD_NUMBER}"){
                    sh 'wget https://ftp.postgresql.org/pub/source/v9.6.9/postgresql-9.6.9.tar.gz --no-check-certificate'
                    sh 'tar xzf postgresql-9.6.9.tar.gz'
                    sh 'ls -alth'
                    dir("postgresql-9.6.9"){
                        stash "psql-files"
                    }
                }
            }},

            curl: { container('curl'){
                dir('scm-curl'){deleteDir()}
                dir('scm-curl'){
                    checkout changelog: false,
                        poll: false,
                        scm: [ $class: 'GitSCM',
                            branches: [[name: 'refs/tags/curl-7_61_0']],
                            doGenerateSubmoduleConfigurations: false,
                            extensions: [],
                            submoduleCfg: [],
                            userRemoteConfigs: [[
                                credentialsId: "${source_credentials}",
                                url: "https://source.developers.google.com/p/${gcpProjectName}/r/curl"
                            ]]
                        ]
                    stash 'curl-files'
                }
            }}
        )}
    }
}

stage("build"){

    parallel pods

}
