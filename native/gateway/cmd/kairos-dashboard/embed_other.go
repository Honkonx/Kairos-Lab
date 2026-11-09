//go:build !windows

package main

import _ "embed"

// Ver KDoc de embed_windows.go — mismo mecanismo, binario sin extensión ".exe". El build de
// Linux de kairos-dashboard sigue bloqueado esta ronda (falta WebKitGTK en el WSL de esta PC,
//) — este archivo deja el mecanismo listo para cuando se pueda compilar,
// con el mismo placeholder de texto hasta que un script de build real lo reemplace.
//
//go:embed embed/kairos-gateway
var embeddedGateway []byte

const embeddedGatewayName = "kairos-gateway"
