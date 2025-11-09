
pipeline {
    agent any

    environment {
        GCP_PROJECT_ID = credentials('gcp-project-id')
        GCP_REGION = 'us-central1'
        HADOOP_CLUSTER_NAME = 'hadoop-cluster'

        SONAR_PROJECT_KEY = 'python-code-disasters'
        SONAR_HOST_URL = 'http://sonarqube.sonarqube.svc.cluster.local:9000'

        RESULTS_BUCKET = "gs://${GCP_PROJECT_ID}-hadoop-results"
        RESULTS_FILE = "line-count-results-${BUILD_NUMBER}.txt"
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timeout(time: 30, unit: 'MINUTES')
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm

                script {
                    env.GIT_COMMIT_SHORT = sh(
                        script: "git rev-parse --short HEAD",
                        returnStdout: true
                    ).trim()
                    env.GIT_COMMIT_MSG = sh(
                        script: "git log -1 --pretty=%B",
                        returnStdout: true
                    ).trim()
                }
            }
        }

        stage('SonarQube Analysis') {
            steps {
                script {
                    def scannerHome = tool 'SonarQube Scanner'

                    writeFile file: 'sonar-project.properties', text: """
sonar.projectKey=${SONAR_PROJECT_KEY}
sonar.projectName=Python Code Disasters
sonar.projectVersion=${BUILD_NUMBER}
sonar.sources=.
sonar.language=py
sonar.sourceEncoding=UTF-8
sonar.python.version=3.8
"""

                    withSonarQubeEnv('SonarQube') {
                        sh """
                            ${scannerHome}/bin/sonar-scanner \
                                -Dsonar.projectKey=${SONAR_PROJECT_KEY} \
                                -Dsonar.sources=.
                        """
                    }
                }
            }
        }

        stage('Quality Gate Check') {
            steps {
                script {
                    sleep(30)

                    def blockerCount = sh(
                        script: """
                            curl -s -u \${SONAR_AUTH_TOKEN}: \
                            "${SONAR_HOST_URL}/api/issues/search?componentKeys=${SONAR_PROJECT_KEY}&severities=BLOCKER&resolved=false" \
                            | grep -o '"total":[0-9]*' | head -1 | cut -d':' -f2
                        """,
                        returnStdout: true
                    ).trim()

                    env.BLOCKER_COUNT = blockerCount ?: '0'

                    if (env.BLOCKER_COUNT.toInteger() > 0) {
                        env.RUN_HADOOP_JOB = 'false'

                        sh """
                            echo "Blocker Issues Details:" > blocker-issues.txt
                            curl -s -u \${SONAR_AUTH_TOKEN}: \
                            "${SONAR_HOST_URL}/api/issues/search?componentKeys=${SONAR_PROJECT_KEY}&severities=BLOCKER&resolved=false" \
                            >> blocker-issues.txt
                        """

                        archiveArtifacts artifacts: 'blocker-issues.txt', fingerprint: true
                    } else {
                        env.RUN_HADOOP_JOB = 'true'
                    }
                }
            }
        }

        stage('Setup Google Cloud SDK') {
            when {
                expression { env.RUN_HADOOP_JOB == 'true' }
            }
            steps {
                script {
                    sh '''
                        export PATH=$HOME/google-cloud-sdk/bin:$PATH
                        if ! command -v gcloud &> /dev/null; then
                            echo "Installing Google Cloud SDK..."
                            rm -rf $HOME/google-cloud-sdk
                            curl https://sdk.cloud.google.com | bash -s -- --disable-prompts --install-dir=$HOME
                        fi
                        gcloud --version
                        gsutil --version
                    '''
                }
            }
        }

        stage('Build Hadoop Job') {
            when {
                expression { env.RUN_HADOOP_JOB == 'true' }
            }
            steps {
                script {
                    def mvnHome = tool name: 'Maven', type: 'maven'
                    dir('hadoop-jobs/line-counter') {
                        sh "${mvnHome}/bin/mvn clean package -DskipTests"
                    }
                }
            }
        }

        stage('Prepare Data for Hadoop') {
            when {
                expression { env.RUN_HADOOP_JOB == 'true' }
            }
            steps {
                sh '''
                    export PATH=$HOME/google-cloud-sdk/bin:$PATH
                    mkdir -p hadoop-input
                    find . -name "*.py" -type f -exec cp {} hadoop-input/ \\;
                    gsutil -m rm -rf gs://${GCP_PROJECT_ID}-hadoop-input/* || true
                    gsutil -m cp -r hadoop-input/* gs://${GCP_PROJECT_ID}-hadoop-input/
                '''
            }
        }

        stage('Submit Hadoop Job') {
            when {
                expression { env.RUN_HADOOP_JOB == 'true' }
            }
            steps {
                sh '''
                    export PATH=$HOME/google-cloud-sdk/bin:$PATH
                    gcloud dataproc jobs submit hadoop \\
                        --cluster=${HADOOP_CLUSTER_NAME} \\
                        --region=${GCP_REGION} \\
                        --jar=hadoop-jobs/line-counter/target/line-counter-1.0-SNAPSHOT.jar \\
                        --class=com.cloudinfra.LineCounter \\
                        -- \\
                        gs://${GCP_PROJECT_ID}-hadoop-input \\
                        gs://${GCP_PROJECT_ID}-hadoop-output/run-${BUILD_NUMBER}
                '''
            }
        }

        stage('Collect and Display Results') {
            when {
                expression { env.RUN_HADOOP_JOB == 'true' }
            }
            steps {
                sh '''
                    export PATH=$HOME/google-cloud-sdk/bin:$PATH
                    sleep 10
                    mkdir -p results
                    gsutil -m cp -r gs://${GCP_PROJECT_ID}-hadoop-output/run-${BUILD_NUMBER}/* results/ || true

                    if [ -f results/part-r-00000 ]; then
                        cat results/part-r-00000 | tee ${RESULTS_FILE}
                    else
                        find results -type f -exec cat {} \\; | tee ${RESULTS_FILE}
                    fi

                    gsutil cp ${RESULTS_FILE} ${RESULTS_BUCKET}/
                '''

                archiveArtifacts artifacts: "${RESULTS_FILE}", fingerprint: true
            }
        }
    }

    post {
        always {
            script {
                echo "Build: ${BUILD_NUMBER} | Commit: ${env.GIT_COMMIT_SHORT} | Blockers: ${env.BLOCKER_COUNT ?: '0'} | Hadoop: ${env.RUN_HADOOP_JOB ?: 'No'}"
            }
        }
    }
}
