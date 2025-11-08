
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
        timestamps()
    }

    stages {
        stage('Checkout') {
            steps {
                echo '=========================================='
                echo 'Stage 1: Checking out source code from GitHub'
                echo '=========================================='

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

                echo "Checked out commit: ${env.GIT_COMMIT_SHORT}"
                echo "Commit message: ${env.GIT_COMMIT_MSG}"
            }
        }

        stage('SonarQube Analysis') {
            steps {
                echo '=========================================='
                echo 'Stage 2: Running SonarQube Code Analysis'
                echo '=========================================='

                script {
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
                            sonar-scanner \
                                -Dsonar.projectKey=${SONAR_PROJECT_KEY} \
                                -Dsonar.sources=. \
                                -Dsonar.host.url=${SONAR_HOST_URL} \
                                -Dsonar.login=\${SONAR_AUTH_TOKEN}
                        """
                    }
                }

                echo "SonarQube analysis completed"
            }
        }

        stage('Quality Gate Check') {
            steps {
                echo '=========================================='
                echo 'Stage 3: Checking SonarQube Quality Gate'
                echo '=========================================='

                script {
                    timeout(time: 5, unit: 'MINUTES') {
                        def qg = waitForQualityGate()

                        echo "Quality Gate Status: ${qg.status}"

                        def blockerCount = sh(
                            script: """
                                curl -s -u \${SONAR_AUTH_TOKEN}: \
                                "${SONAR_HOST_URL}/api/issues/search?componentKeys=${SONAR_PROJECT_KEY}&severities=BLOCKER&resolved=false" \
                                | grep -o '"total":[0-9]*' | head -1 | cut -d':' -f2
                            """,
                            returnStdout: true
                        ).trim()

                        env.BLOCKER_COUNT = blockerCount ?: '0'

                        echo "Number of blocker issues found: ${env.BLOCKER_COUNT}"

                        if (env.BLOCKER_COUNT.toInteger() > 0) {
                            env.RUN_HADOOP_JOB = 'false'
                            echo "⚠️  BLOCKER ISSUES FOUND - Hadoop job will NOT be executed"

                            sh """
                                echo "Blocker Issues Details:" > blocker-issues.txt
                                curl -s -u \${SONAR_AUTH_TOKEN}: \
                                "${SONAR_HOST_URL}/api/issues/search?componentKeys=${SONAR_PROJECT_KEY}&severities=BLOCKER&resolved=false" \
                                >> blocker-issues.txt
                            """

                            archiveArtifacts artifacts: 'blocker-issues.txt', fingerprint: true
                        } else {
                            env.RUN_HADOOP_JOB = 'true'
                            echo "✓ No blocker issues found - Hadoop job will be executed"
                        }
                    }
                }
            }
        }

        stage('Build Hadoop Job') {
            when {
                expression { env.RUN_HADOOP_JOB == 'true' }
            }
            steps {
                echo '=========================================='
                echo 'Stage 4: Building Hadoop MapReduce Job'
                echo '=========================================='

                dir('hadoop-jobs/line-counter') {
                    sh '''
                        mvn clean package -DskipTests

                        echo "JAR file created:"
                        ls -lh target/*.jar
                    '''
                }
            }
        }

        stage('Prepare Data for Hadoop') {
            when {
                expression { env.RUN_HADOOP_JOB == 'true' }
            }
            steps {
                echo '=========================================='
                echo 'Stage 5: Preparing Data for Hadoop Processing'
                echo '=========================================='

                sh '''
                    mkdir -p hadoop-input
                    find . -name "*.py" -type f -exec cp {} hadoop-input/ \\;

                    find . -name "*.py" -type f > file-list.txt

                    echo "Files to be processed:"
                    cat file-list.txt

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
                echo '=========================================='
                echo 'Stage 6: Submitting Hadoop MapReduce Job'
                echo '=========================================='

                script {
                    sh '''
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

                echo "Hadoop job submitted successfully"
            }
        }

        stage('Collect and Display Results') {
            when {
                expression { env.RUN_HADOOP_JOB == 'true' }
            }
            steps {
                echo '=========================================='
                echo 'Stage 7: Collecting and Displaying Results'
                echo '=========================================='

                sh '''
                    sleep 10

                    mkdir -p results
                    gsutil -m cp -r gs://${GCP_PROJECT_ID}-hadoop-output/run-${BUILD_NUMBER}/* results/ || true

                    echo ""
                    echo "=========================================="
                    echo "HADOOP JOB RESULTS - Line Count per File"
                    echo "=========================================="

                    if [ -f results/part-r-00000 ]; then
                        cat results/part-r-00000 | while read line; do
                            echo "$line"
                        done | tee ${RESULTS_FILE}
                    else
                        echo "Results file not found. Checking all result files..."
                        find results -type f -exec cat {} \\; | tee ${RESULTS_FILE}
                    fi

                    echo "=========================================="

                    gsutil cp ${RESULTS_FILE} ${RESULTS_BUCKET}/

                    echo ""
                    echo "Results saved to: ${RESULTS_BUCKET}/${RESULTS_FILE}"
                '''

                archiveArtifacts artifacts: "${RESULTS_FILE}", fingerprint: true
            }
        }
    }

    post {
        always {
            echo '=========================================='
            echo 'Pipeline Execution Summary'
            echo '=========================================='

            script {
                echo "Build Number: ${BUILD_NUMBER}"
                echo "Commit: ${env.GIT_COMMIT_SHORT}"
                echo "Blocker Issues: ${env.BLOCKER_COUNT ?: 'Not checked'}"
                echo "Hadoop Job Executed: ${env.RUN_HADOOP_JOB ?: 'No'}"

                if (env.RUN_HADOOP_JOB == 'true') {
                    echo "Results Location: ${RESULTS_BUCKET}/${RESULTS_FILE}"
                }
            }

            cleanWs()
        }

        success {
            echo '✓ Pipeline completed successfully!'
        }

        failure {
            echo '✗ Pipeline failed. Check logs for details.'
        }
    }
}
