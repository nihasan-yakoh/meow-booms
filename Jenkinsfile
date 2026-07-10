pipeline {
    agent any

    parameters {
        string(name: 'SPRING_PROFILES_ACTIVE', defaultValue: 'dev', description: 'Spring active profile (e.g. prod, dev, zti)')

        // ── Deploy Target (comma-separated for multi-server) ──
       string(name: 'REMOTE_SERVERS', defaultValue: '5.223.49.2', description: 'Target server IPs (comma-separated)')
       string(name: 'REMOTE_USER',    defaultValue: 'dev_team',   description: 'SSH username for target server')
       string(name: 'APP_CONTAINER_PORT', defaultValue: '9999',       description: 'App exposed port')
    }

    environment {
        SERVER_CREDENTIALS_ID   = 'z2-dev-credential'
        DOCKER_IMAGE_NAME       = 'meow-boom'
        APP_CONTAINER_NAME      = 'meow-boom'
        HARBOR_URL              = 'harbor.ztidev.com'
        HARBOR_PROJECT          = 'demo'
        HARBOR_CREDENTIALS_ID   = 'SSH_ADMIN_DEPLOY_SECRET'
    }

    stages {
          stage('Checkout') {
                  steps {
                      checkout scm
                  }
          }

          stage('Set Version') {
                steps{
                    script {
                        if(env.GIT_COMMIT){
                            env.APP_VERSION = env.GIT_COMMIT.take(7)
                        }else{
                            env.APP_VERSION = "latest"
                        }
                        env.FULL_IMAGE_NAME = "${HARBOR_URL}/${HARBOR_PROJECT}/${DOCKER_IMAGE_NAME}:${env.APP_VERSION}"
                        echo "Image: ${env.FULL_IMAGE_NAME}"
                    }
                }
          }

          stage('Build Docker Image') {
                steps{
                    sh "docker build -t ${FULL_IMAGE_NAME} ."
                }
          }

          stage('Push Image to Harbor') {
                steps {
                    withCredentials([usernamePassword(credentialsId: HARBOR_CREDENTIALS_ID, usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
                        sh """
                            docker login -u \$REG_USER -p \$REG_PASS ${HARBOR_URL}
                            docker push ${FULL_IMAGE_NAME}
                        """
                    }
                }
          }

          stage('Deploy to Remote Servers') {
            steps {
            withCredentials([sshUserPrivateKey(credentialsId: SERVER_CREDENTIALS_ID, keyFileVariable: 'SSH_KEY'),
                             usernamePassword(credentialsId: HARBOR_CREDENTIALS_ID, usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]){
                script {
                    def servers = params.REMOTE_SERVERS.split(',').collect { it.trim() }
                    for (server in servers) {
                         echo "=== Deploying to ${server} ==="

                           sh """
                               ssh -i \$SSH_KEY -o StrictHostKeyChecking=no ${params.REMOTE_USER}@${server} '
                                   docker login -u ${REG_USER} -p ${REG_PASS} ${HARBOR_URL}
                                   docker pull ${FULL_IMAGE_NAME}

                                   docker volume create demo-logs || true

                                   # Fix log volume permissions for non-root appuser (UID 1000)
                                   docker run --rm -v demo-logs:/logs alpine sh -c "chown -R 1000:1000 /logs"

                                   docker stop ${APP_CONTAINER_NAME} || true
                                   docker rm ${APP_CONTAINER_NAME} || true

                                   # --- App container ---
                                   docker run -d --name ${APP_CONTAINER_NAME} \\
                                   --add-host=host.docker.internal:host-gateway \\
                                   -p ${params.APP_CONTAINER_PORT}:${params.APP_CONTAINER_PORT} \\
                                   -e "TZ=Asia/Bangkok" \\
                                   -v demo-logs:/logs \\
                                   -v /data:/data \\
                                   -e "SPRING_PROFILES_ACTIVE=${params.SPRING_PROFILES_ACTIVE}" \\
                                   --restart always \\
                                   ${FULL_IMAGE_NAME}

                                   docker logout ${HARBOR_URL}
                                   docker image prune -af
                               '
                           """
                        echo "=== Done: ${server} ==="
                    }
                }
              }
            }
          }
    }
}