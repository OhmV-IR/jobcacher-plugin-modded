pipeline {
    agent none

    stages {
        stage('Build matrix') {
            matrix {
                axes {
                    axis {
                        name 'JDK'
                        values '21', '25'
                    }
                }

                agent { label 'linux' }
                tools { jdk "${JDK}" }

                stages {
                    stage("Checkout") {
                        steps {
                            checkout scm
                        }
                    }

                    stage("Build plugin") {
                        steps {
                            sh "mvn -U -DforkCount=1C clean verify"
                        }
                    }

                    stage("Upload artifact") {
                        steps {
                            archiveArtifacts artifacts: "target/jobcacher.hpi", fingerprint: true
                        }
                    }
                }
            }
        }
    }
}

