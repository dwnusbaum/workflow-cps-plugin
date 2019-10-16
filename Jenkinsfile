stage('Build') {
    node('master') {
        checkout scm
        sh('ls -l && mvn -B clean verify -Dtest=CpsFlowDefinition2Test')
    }
}
stage('Demo') {
    echo('Hi!')
    sleep 10
}
