node('master') {
    checkout scm
    sh('ls -l && mvn -B clean verify -Dtest=CpsFlowDefinition2Test')
}
