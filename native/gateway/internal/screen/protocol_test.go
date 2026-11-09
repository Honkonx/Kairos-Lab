package screen

import (
	"bytes"
	"testing"
)

func TestHeaderRoundTrip(t *testing.T) {
	cases := []Frame{
		{PTS: 123456, Config: false, KeyFrame: false, Size: 42},
		{PTS: 999, Config: false, KeyFrame: true, Size: 0},
		{PTS: 0, Config: true, KeyFrame: false, Size: 7}, // el pts se ignora si Config es true
	}
	for _, f := range cases {
		hdr := EncodeHeader(f)
		if len(hdr) != HeaderSize {
			t.Fatalf("la cabecera debe medir %d bytes, midió %d", HeaderSize, len(hdr))
		}
		got, err := DecodeHeader(hdr)
		if err != nil {
			t.Fatalf("decode: %v", err)
		}
		want := f
		if want.Config {
			want.PTS = 0
		}
		if got != want {
			t.Errorf("round-trip distinto: entrada %+v, decodificado %+v", want, got)
		}
	}
}

func TestDecodeHeaderRequiresFullSize(t *testing.T) {
	if _, err := DecodeHeader(make([]byte, 5)); err == nil {
		t.Error("debe fallar con menos de 12 bytes")
	}
}

func TestReadFrameRoundTrip(t *testing.T) {
	payload := []byte("hola-frame-de-prueba")
	frame := Frame{PTS: 777, KeyFrame: true, Size: int32(len(payload))}
	raw := EncodeFrame(frame, payload)

	got, gotRaw, err := ReadFrame(bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("ReadFrame: %v", err)
	}
	if got.PTS != frame.PTS || got.KeyFrame != frame.KeyFrame || got.Size != frame.Size {
		t.Errorf("metadata distinta: %+v vs %+v", got, frame)
	}
	if !bytes.Equal(gotRaw, raw) {
		t.Error("los bytes crudos reenviables deben coincidir con lo codificado")
	}
}

func TestReadFrameRejectsOversized(t *testing.T) {
	hdr := EncodeHeader(Frame{Size: maxFrameSize + 1})
	if _, _, err := ReadFrame(bytes.NewReader(hdr)); err != ErrFrameTooLarge {
		t.Errorf("un tamaño fuera de rango debe dar ErrFrameTooLarge, dio %v", err)
	}
}

func TestReadFrameRejectsNegativeSize(t *testing.T) {
	hdr := EncodeHeader(Frame{Size: -1})
	if _, _, err := ReadFrame(bytes.NewReader(hdr)); err != ErrFrameTooLarge {
		t.Errorf("un tamaño negativo debe dar ErrFrameTooLarge, dio %v", err)
	}
}

func TestReadFrameStopsOnShortHeader(t *testing.T) {
	// io.ReadFull devuelve io.ErrUnexpectedEOF (o io.EOF si no hay nada) con una cabecera cortada;
	// lo único que importa acá es que ReadFrame nunca devuelva éxito con datos incompletos.
	if _, _, err := ReadFrame(bytes.NewReader([]byte{1, 2, 3})); err == nil {
		t.Error("una cabecera incompleta debe devolver un error")
	}
}

func TestReadFrameMultipleInSequence(t *testing.T) {
	var buf bytes.Buffer
	buf.Write(EncodeFrame(Frame{Config: true, Size: 3}, []byte("abc")))
	buf.Write(EncodeFrame(Frame{PTS: 1, KeyFrame: true, Size: 4}, []byte("wxyz")))

	f1, _, err := ReadFrame(&buf)
	if err != nil || !f1.Config {
		t.Fatalf("primer frame debe ser config: %+v %v", f1, err)
	}
	f2, raw2, err := ReadFrame(&buf)
	if err != nil || !f2.KeyFrame || f2.PTS != 1 {
		t.Fatalf("segundo frame debe ser keyframe con pts=1: %+v %v", f2, err)
	}
	if !bytes.Equal(raw2[HeaderSize:], []byte("wxyz")) {
		t.Error("el payload del segundo frame no coincide")
	}
}
