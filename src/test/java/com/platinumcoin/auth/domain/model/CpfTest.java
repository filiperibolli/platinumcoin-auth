package com.platinumcoin.auth.domain.model;

import com.platinumcoin.auth.domain.error.InvalidCpfException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CpfTest {

    @Test
    void aceitaCpfValidoSemMascara() {
        assertEquals("52998224725", Cpf.parse("52998224725").digits());
    }

    @Test
    void aceitaCpfValidoComMascara() {
        assertEquals("39053344705", Cpf.parse("390.533.447-05").digits());
    }

    @Test
    void rejeitaDigitoVerificadorErrado() {
        assertThrows(InvalidCpfException.class, () -> Cpf.parse("52998224724"));
    }

    @Test
    void rejeitaTodosDigitosIguais() {
        assertThrows(InvalidCpfException.class, () -> Cpf.parse("11111111111"));
    }

    @Test
    void rejeitaTamanhoErrado() {
        assertThrows(InvalidCpfException.class, () -> Cpf.parse("1234567890"));
    }

    @Test
    void rejeitaNuloOuVazio() {
        assertThrows(InvalidCpfException.class, () -> Cpf.parse(null));
        assertThrows(InvalidCpfException.class, () -> Cpf.parse("  "));
    }
}
