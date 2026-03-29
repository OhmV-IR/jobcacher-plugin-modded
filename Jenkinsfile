pipeline {
    agent none

    stages {
        stage('Build matrix') {
            matrix {
                axes {
                    axis {
                        name 'JDK_VERSION'
                        values '21', '25'
                    }
                }

                agent { label 'linux' }
                tools { 
			jdk "${JDK_VERSION}"
			maven '3.9.14'
		}

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
			    sh "mkdir -p output"
			    sh "cp target/jobcacher.hpi output/jobcacher-jvm-${JDK_VERSION}.hpi"
                            archiveArtifacts artifacts: "output/jobcacher-jvm-${JDK_VERSION}.hpi", fingerprint: true
                        }
                    }
                }
            }
        }
    }
}

