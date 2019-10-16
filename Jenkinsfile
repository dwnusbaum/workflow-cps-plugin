stage('Build') {
    node('master') {
        checkout scm
        sh('ls -l && mvn -B clean verify -Dtest=CpsFlowDefinition2Test')
    }
}
stage('Demo') {
    echo('Hello!')
    sleep 10
}
